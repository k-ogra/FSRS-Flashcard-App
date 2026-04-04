package com.kogura.FSRS_Flashcard_App.controller;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kogura.FSRS_Flashcard_App.service.S3Service;

@RestController
@RequestMapping("/api/v0/s3")
public class S3Controller {
  private final S3Service s3Service;
  
  public S3Controller(S3Service s3Service) {
    this.s3Service = s3Service;
  }

  @GetMapping("/presigned-upload")
  public String getPresignedGetUrl(@RequestParam String flashcardId, @RequestParam String fileName, @RequestParam boolean isQuestion) {
    String key = String.format("uploads/%s/%s/%s",
    SecurityContextHolder.getContext().getAuthentication().getName(),
    UUID.randomUUID().toString(),
    fileName
  );

    Map<String, String> metadata = new HashMap<>();
    metadata.put("flashcardid", flashcardId);
    metadata.put("filename", fileName);
    metadata.put("isquestion", String.valueOf(isQuestion));
    return s3Service.createPresignedUploadUrl(key, metadata);
  }

  @GetMapping("/presigned-download")
  public String getPresignedDownloadUrl(@RequestParam String key) {
    return s3Service.createPresignedDownloadUrl(key);
  }

}
