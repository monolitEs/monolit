package org.monolites.monolit.parser.utils;


import org.monolites.monolit.models.exception.NewsParseException;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URL;

@Slf4j
public class RssData {

    private RssData(){
        throw new IllegalStateException("Utility class");
    }

    public static SyndFeed getData(String link) {
        try {
            URL feedSource = new URL(link);
            SyndFeedInput input = new SyndFeedInput();
            return input.build(new XmlReader(feedSource));
        } catch (FeedException | IOException e) {
            log.error(e.getMessage());
            throw new NewsParseException(e.getMessage(), e);
        }
    }
}
