package org.monolites.monolit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class MonolitApplication {

    public static void main(String[] args) {
        SpringApplication.run(MonolitApplication.class, args);
    }
}
