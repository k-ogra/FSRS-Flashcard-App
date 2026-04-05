package com.kogura.FSRS_Flashcard_App.controller;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.kogura.FSRS_Flashcard_App.dto.PresignedPostResponse;
import com.kogura.FSRS_Flashcard_App.service.S3Service;

@RestController
@RequestMapping("/api/v0/s3")
public class S3Controller {

  private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp");
  private static final Set<String> AUDIO_EXTENSIONS = Set.of("mp3", "wav", "ogg");

  private final S3Service s3Service;

  public S3Controller(S3Service s3Service) {
    this.s3Service = s3Service;
  }

  @GetMapping("/presigned-upload")
  public PresignedPostResponse getPresignedUploadData(@RequestParam String flashcardId, @RequestParam String fileName, @RequestParam boolean isQuestion) {
    // Validate file extension
    int dotIndex = fileName.lastIndexOf('.');
    if (dotIndex < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File must have an extension.");
    }
    String extension = fileName.substring(dotIndex + 1).toLowerCase();

    String contentTypePrefix; 
    
    if (IMAGE_EXTENSIONS.contains(extension)) {
      contentTypePrefix = "image/";
    } else if (AUDIO_EXTENSIONS.contains(extension)) {
      contentTypePrefix = "audio/";
    } else {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Unsupported file type. Only image (.jpg, .png, .gif, .webp) and audio (.mp3, .wav, .ogg) files are allowed.");
    }

    String key = String.format("uploads/%s/%s/%s",
      SecurityContextHolder.getContext().getAuthentication().getName(),
      UUID.randomUUID().toString(),
      fileName
    );

    Map<String, String> metadata = new HashMap<>();
    metadata.put("flashcardid", flashcardId);
    metadata.put("filename", fileName);
    metadata.put("isquestion", String.valueOf(isQuestion));

    return s3Service.createPresignedPostData(key, metadata, contentTypePrefix);
  }

  @GetMapping("/presigned-download")
  public String getPresignedDownloadUrl(@RequestParam String key) {
    return s3Service.createPresignedDownloadUrl(key);
  }

}
