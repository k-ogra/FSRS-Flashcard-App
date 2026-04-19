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

/**
 * REST controller for S3 media upload and download operations.
 *
 * <p>Exposes two endpoints under {@code /api/v0/s3}:
 * <ul>
 *   <li>{@code GET /presigned-upload} — generates a presigned POST policy for browser-direct
 *       uploads to S3, restricted to supported image and audio file types.</li>
 *   <li>{@code GET /presigned-download} — generates a short-lived presigned GET URL for
 *       downloading an existing S3 object.</li>
 * </ul>
 *
 * <p>Both endpoints require an authenticated session; Spring Security's filter chain
 * rejects anonymous requests with 401 before the controller is reached.
 */
@RestController
@RequestMapping("/api/v0/s3")
public class S3Controller {

  /**
   * Supported image file extensions. Extension matching is case-insensitive.
   * Uploads with one of these extensions receive a {@code "image/"} content-type prefix.
   */
  private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp");

  /**
   * Supported audio file extensions. Extension matching is case-insensitive.
   * Uploads with one of these extensions receive an {@code "audio/"} content-type prefix.
   */
  private static final Set<String> AUDIO_EXTENSIONS = Set.of("mp3", "wav", "ogg");

  /**
   * Service delegate that performs the actual AWS S3 presigned-URL operations.
   */
  private final S3Service s3Service;

  /**
   * Constructs the controller with its required S3 service dependency.
   *
   * @param s3Service the service used to create presigned upload and download URLs
   */
  public S3Controller(S3Service s3Service) {
    this.s3Service = s3Service;
  }

  /**
   * Generates a presigned POST policy for a browser-direct upload to S3.
   *
   * <p>Validates that {@code fileName} has a supported extension (image or audio).
   * The S3 object key is scoped to the authenticated user:
   * {@code uploads/{username}/{uuid}/{fileName}}.
   * The policy embeds {@code flashcardid}, {@code filename}, and {@code isquestion}
   * as S3 user-defined metadata so the server can validate the upload on the S3 event.
   *
   * @param flashcardId the ID of the flashcard this media belongs to; stored as S3 metadata
   * @param fileName    the original client-side file name, used to derive the extension and key
   * @param isQuestion  {@code true} if the media is for the question side; {@code false} for the answer
   * @return a {@link PresignedPostResponse} containing the S3 endpoint URL and the signed form fields
   * @throws ResponseStatusException 400 if {@code fileName} has no extension or an unsupported one
   */
  @GetMapping("/presigned-upload")
  public PresignedPostResponse getPresignedUploadData(@RequestParam String flashcardId, @RequestParam String fileName, @RequestParam boolean isQuestion) {
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

  /**
   * Generates a short-lived presigned GET URL for downloading an S3 object.
   *
   * <p>The {@code key} parameter is forwarded verbatim to {@link S3Service#createPresignedDownloadUrl};
   * no ownership check is performed here — callers must only request keys they are authorised to read.
   *
   * @param key the S3 object key to generate a download URL for
   * @return the presigned download URL as a plain string
   */
  @GetMapping("/presigned-download")
  public String getPresignedDownloadUrl(@RequestParam String key) {
    return s3Service.createPresignedDownloadUrl(key);
  }

}
