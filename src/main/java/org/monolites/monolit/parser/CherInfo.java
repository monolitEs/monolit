package org.monolites.monolit.parser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.monolites.monolit.models.dtos.ImageDto;
import org.monolites.monolit.models.dtos.NewsData;
import org.monolites.monolit.models.exception.NewsParseException;
import org.monolites.monolit.models.entities.NewsParserState;
import org.monolites.monolit.repositories.NewsParserStateRepository;
import org.monolites.monolit.parser.utils.ImageDownloader;
import org.monolites.monolit.parser.utils.RssData;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.IOException;
import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class CherInfo implements Parser {

    private static final String SOURCE_KEY = "cherinfo";
    private static final int INITIAL_NEWS_COUNT = 5;
    private final NewsParserStateRepository stateRepository;

    @Value("${monolit.news.cherinfo.rss-url}")
    private String rssLink = "https://cherinfo.ru/rss/news";

    private Date lastPubDate;

    @Override
    public List<NewsData> parseData() {
        List<NewsData> newsList = new ArrayList<>();
        if (lastPubDate == null) {
            lastPubDate = stateRepository.findById(SOURCE_KEY)
                    .map(NewsParserState::getLastPubDate).map(Date::from).orElse(null);
            log.info("Restored {} news checkpoint: {}", SOURCE_KEY, lastPubDate);
        }
        SyndFeed feed = checkNewsUpdate();

        if (feed == null) {
            return newsList;
        }

        feed.setEntries(findFirstNews(feed.getEntries()));

        for (SyndEntry entry : feed.getEntries()) {
            NewsData newsData = getNews(entry.getLink());
            newsData.setTitle(entry.getTitle());
            newsData.setDate(entry.getPublishedDate().toInstant());

            newsList.add(newsData);
        }

        return newsList;
    }

    @Override
    public String getName() {
        return "ЧерИнфо";
    }

    private SyndFeed checkNewsUpdate() {
        SyndFeed feed = RssData.getData(rssLink);
        Date lastNewsDate = feed.getEntries().get(0).getPublishedDate();

        if (lastNewsDate.equals(lastPubDate)) {
            return null;
        }

        return feed;
    }

    private List<SyndEntry> findFirstNews(List<SyndEntry> entries) {
        int indx = 0;

        for (SyndEntry entry : entries) {
            if (entry.getPublishedDate().equals(lastPubDate)
                    || (lastPubDate == null && indx == INITIAL_NEWS_COUNT)) {
                break;
            }
            indx++;
        }

        entries = entries.subList(0, indx);
        NewsParserState state = new NewsParserState();
        state.setSourceKey(SOURCE_KEY);
        state.setLastPubDate(entries.get(0).getPublishedDate().toInstant());
        stateRepository.save(state);
        lastPubDate = entries.get(0).getPublishedDate();
        Collections.reverse(entries);

        return entries;
    }

    private NewsData getNews(String link) {
        try {
            Document doc = Jsoup.connect(link).get();
            Element element = doc.getElementsByClass("article-text").first();
            StringBuilder description = new StringBuilder();
            NewsData news = new NewsData();

            for (Element e : element.getAllElements().subList(1, element.getAllElements().size())) {

                switch (e.tagName()) {
                    case "p":
                        description.append((e.text().isEmpty() || !e.getElementsByTag("iframe").isEmpty()) ? "" : e.text() + "\n\n");
                        Element img = e.getElementsByTag("img").first();
                        if (img != null) {
                            news.setImages(getImagesLinks(new Elements(List.of(img))));
                        }
                        break;
                    case "div":
                        if(e.attr("class").equals("fotorama")){
                            news.setImages(getImagesLinks(e.getElementsByTag("a")));
                        }
                        break;
                    default:
                }
            }

            news.setDescription(description.toString());
            return news;

        } catch (IOException e) {
            throw new NewsParseException("Ошибка парсинга", e);
        }
    }

    private Map<String, File> getImagesLinks(Elements images) {
        Map<String, File> result = new HashMap<>();

        for (Element img : images) {
            String link = img.attr(img.attr("href").isEmpty() ? "src" : "href");
            ImageDto imageDto = ImageDownloader.downloadImage(link);
            File file = new File(imageDto.getPath().toUri());
            result.put(imageDto.getName(), file);
        }
        return result;
    }
}
