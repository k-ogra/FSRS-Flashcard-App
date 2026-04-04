package com.kogura.FSRS_Flashcard_App;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FsrsFlashcardAppApplication {

	public static void main(String[] args) {
		SpringApplication.run(FsrsFlashcardAppApplication.class, args);
	}

}
