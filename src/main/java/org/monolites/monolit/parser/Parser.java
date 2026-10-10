package org.monolites.monolit.parser;

import org.monolites.monolit.models.dtos.NewsData;

import java.util.List;


public interface Parser {

    List<NewsData> parseData();
    String getName();
}
