package org.monolites.monolit.models.dtos;

import lombok.Getter;
import lombok.Setter;

import java.io.File;
import java.time.Instant;
import java.util.Map;

@Getter
@Setter
public class NewsData {
    private String title;
    private String description;
    private Instant date;
    private Map<String, File> images;

    public boolean isEmpty(){
        return description == null || description.isEmpty();
    }
}
