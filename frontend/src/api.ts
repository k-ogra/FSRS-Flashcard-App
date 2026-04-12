const API_ROOT = `${import.meta.env.VITE_API_BASE_URL}/api/v0`;
const API_BASE = `${API_ROOT}/auth`;

export interface AuthResponse {
  message: string;
  username: string | null;
}

export interface MediaMetadataDTO {
  s3Key: string | null;
  name: string | null;
  presignedDownloadUrl: string | null;
}

export interface Deck {
  id: number;
  name: string;
  isPublic: boolean;
  isShared: boolean;
  createdAt: string;
  flashcards: {
    id: number;
    question: string;
    answer: string;
    createdAt: string;
    stability: number | null;
    difficulty: number | null;
    state: FsrsState | null;
    step: number | null;
    dueDate: string | null;
    lastReview: string | null;
    questionMediaMetadata: MediaMetadataDTO | null;
    answerMediaMetadata: MediaMetadataDTO | null;
  }[];
}

export type FsrsState = "LEARNING" | "REVIEW" | "RELEARNING";

export interface FlashcardStudy {
  id: number;
  question: string;
  answer: string;
  state: "NEW" | "LEARNING" | "REVIEW";
  dueDate: string | null;
  againInterval: string;
  hardInterval: string;
  goodInterval: string;
  easyInterval: string;
  questionMediaUrl: string | null;
  questionMediaName: string | null;
  answerMediaUrl: string | null;
  answerMediaName: string | null;
}

export interface DeckStats {
  newCount: number;
  learningCount: number;
  reviewCount: number;
}

export type Grade = "AGAIN" | "HARD" | "GOOD" | "EASY";

export interface DeckSummary {
  id: number;
  name: string;
  isPublic: boolean;
  ownerUsername: string;
  sharedByUsername: string | null;
  createdAt: string;
  flashcardCount: number;
}

export interface UserSummary {
  id: number;
  username: string;
}

export class ApiError extends Error {
  status: number;
  constructor(message: string, status: number) {
    super(message);
    this.status = status;
  }
}

function parseCsrfFromCookie(): string | null {
  const match = document.cookie
    .split("; ")
    .find((row) => row.startsWith("XSRF-TOKEN="));
  return match ? decodeURIComponent(match.split("=")[1]) : null;
}

async function getCsrfToken(): Promise<string> {
  let token = parseCsrfFromCookie();
  if (token) return token;

  await fetch(`${API_BASE}/csrf`, { credentials: "include" });

  token = parseCsrfFromCookie();
  if (!token) throw new Error("Failed to obtain CSRF token");
  return token;
}

export async function signup(
  username: string,
  password: string,
): Promise<AuthResponse> {
  const csrfToken = await getCsrfToken();
  const res = await fetch(`${API_BASE}/signup`, {
    method: "POST",
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      "X-XSRF-TOKEN": csrfToken,
    },
    body: JSON.stringify({ username, password }),
  });
  const data: AuthResponse = await res.json();
  if (!res.ok) throw new ApiError(data.message, res.status);
  return data;
}

export async function login(
  username: string,
  password: string,
): Promise<AuthResponse> {
  const csrfToken = await getCsrfToken();
  const res = await fetch(`${API_BASE}/login`, {
    method: "POST",
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      "X-XSRF-TOKEN": csrfToken,
    },
    body: JSON.stringify({ username, password }),
  });
  const data: AuthResponse = await res.json();
  if (!res.ok) throw new ApiError(data.message, res.status);
  return data;
}

export async function logout(): Promise<void> {
  const csrfToken = await getCsrfToken();
  await fetch(`${API_BASE}/logout`, {
    method: "POST",
    credentials: "include",
    headers: {
      "X-XSRF-TOKEN": csrfToken,
    },
  });
}

export async function getAuthenticated(): Promise<AuthResponse> {
  const res = await fetch(`${API_BASE}/authenticated`, {
    credentials: "include",
  });
  const data: AuthResponse = await res.json();
  if (!res.ok) throw new ApiError(data.message, res.status);
  return data;
}

export interface UserSettings {
  reviewAheadMinutes: number;
  dailyNewCardLimit: number;
  dailyReviewLimit: number;
}

export async function getUserSettings(): Promise<UserSettings> {
  const res = await fetch(`${API_BASE}/settings`, {
    credentials: "include",
  });
  if (!res.ok) {
    const data = await res
      .json()
      .catch(() => ({ message: "Failed to load settings" }));
    throw new ApiError(data.message, res.status);
  }
  return res.json();
}

export async function updateUserSettings(
  settings: UserSettings,
): Promise<UserSettings> {
  const csrfToken = await getCsrfToken();
  const res = await fetch(`${API_BASE}/settings`, {
    method: "PUT",
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      "X-XSRF-TOKEN": csrfToken,
    },
    body: JSON.stringify(settings),
  });
  if (!res.ok) {
    const data = await res
      .json()
      .catch(() => ({ message: "Failed to update settings" }));
    throw new ApiError(data.message, res.status);
  }
  return res.json();
}

export async function deleteAccount(): Promise<void> {
  const csrfToken = await getCsrfToken();
  const res = await fetch(`${API_BASE}/account`, {
    method: "DELETE",
    credentials: "include",
    headers: {
      "X-XSRF-TOKEN": csrfToken,
    },
  });
  if (!res.ok) {
    const data = await res
      .json()
      .catch(() => ({ message: "Failed to delete account" }));
    throw new ApiError(data.message, res.status);
  }
}

export async function getDeck(id: number): Promise<Deck> {
  const res = await fetch(`${API_ROOT}/decks/${id}`, {
    credentials: "include",
  });
  if (!res.ok) {
    const data = await res.json().catch(() => ({ message: "Failed to load deck" }));
    throw new ApiError(data.message, res.status);
  }
  return res.json();
}

export async function getDecks(): Promise<Deck[]> {
  const res = await fetch(`${API_ROOT}/decks`, {
    credentials: "include",
  });
  if (!res.ok) {
    const data = await res.json().catch(() => ({ message: "Unauthorized" }));
    throw new ApiError(data.message, res.status);
  }
  return res.json();
}

export async function createDeck(
  name: string,
  isPublic: boolean,
): Promise<Deck> {
  const csrfToken = await getCsrfToken();
  const res = await fetch(`${API_ROOT}/decks`, {
    method: "POST",
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      "X-XSRF-TOKEN": csrfToken,
    },
    body: JSON.stringify({ name, isPublic }),
  });
  if (!res.ok) {
    const data = await res
      .json()
      .catch(() => ({ message: "Failed to create deck" }));
    throw new ApiError(data.message, res.status);
  }
  return res.json();
}

export async function deleteDeck(deckId: number): Promise<void> {
  const csrfToken = await getCsrfToken();
  const res = await fetch(`${API_ROOT}/decks/${deckId}`, {
    method: "DELETE",
    credentials: "include",
    headers: {
      "X-XSRF-TOKEN": csrfToken,
    },
  });
  if (!res.ok) {
    const data = await res
      .json()
      .catch(() => ({ message: "Failed to delete deck" }));
    throw new ApiError(data.message, res.status);
  }
}

export async function getPublicDecks(): Promise<DeckSummary[]> {
  const res = await fetch(`${API_ROOT}/decks/public`, {
    credentials: "include",
  });
  if (!res.ok) {
    const data = await res
      .json()
      .catch(() => ({ message: "Failed to load public decks" }));
    throw new ApiError(data.message, res.status);
  }
  return res.json();
}

export async function getSharedDecks(): Promise<DeckSummary[]> {
  const res = await fetch(`${API_ROOT}/decks/shared`, {
    credentials: "include",
  });
  if (!res.ok) {
    const data = await res
      .json()
      .catch(() => ({ message: "Failed to load shared decks" }));
    throw new ApiError(data.message, res.status);
  }
  return res.json();
}

export async function toggleDeckVisibility(
  deckId: number,
  isPublic: boolean,
): Promise<DeckSummary> {
  const csrfToken = await getCsrfToken();
  const res = await fetch(`${API_ROOT}/decks/${deckId}/visibility`, {
    method: "PATCH",
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      "X-XSRF-TOKEN": csrfToken,
    },
    body: JSON.stringify({ isPublic }),
  });
  if (!res.ok) {
    const data = await res
      .json()
      .catch(() => ({ message: "Failed to update visibility" }));
    throw new ApiError(data.message, res.status);
  }
  return res.json();
}

export async function getDeckRecipients(
  deckId: number,
): Promise<UserSummary[]> {
  const res = await fetch(`${API_ROOT}/decks/${deckId}/share`, {
    credentials: "include",
  });
  if (!res.ok) {
    const data = await res
      .json()
      .catch(() => ({ message: "Failed to load recipients" }));
    throw new ApiError(data.message, res.status);
  }
  return res.json();
}

export async function shareDeck(
  deckId: number,
  username: string,
): Promise<{ message: string }> {
  const csrfToken = await getCsrfToken();
  const res = await fetch(`${API_ROOT}/decks/${deckId}/share`, {
    method: "POST",
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      "X-XSRF-TOKEN": csrfToken,
    },
    body: JSON.stringify({ username }),
  });
  const data = await res.json();
  if (!res.ok) throw new ApiError(data.message, res.status);
  return data;
}

export async function unshareDeck(
  deckId: number,
  userId: number,
): Promise<void> {
  const csrfToken = await getCsrfToken();
  const res = await fetch(`${API_ROOT}/decks/${deckId}/share/${userId}`, {
    method: "DELETE",
    credentials: "include",
    headers: {
      "X-XSRF-TOKEN": csrfToken,
    },
  });
  if (!res.ok) {
    const data = await res
      .json()
      .catch(() => ({ message: "Failed to unshare deck" }));
    throw new ApiError(data.message, res.status);
  }
}

export async function getStudyQueue(
  deckId: number,
): Promise<FlashcardStudy[]> {
  const res = await fetch(`${API_ROOT}/decks/${deckId}/study/queue`, {
    credentials: "include",
  });
  if (!res.ok) {
    const data = await res
      .json()
      .catch(() => ({ message: "Failed to load study queue" }));
    throw new ApiError(data.message, res.status);
  }
  return res.json();
}

export async function submitReview(
  deckId: number,
  flashcardId: number,
  grade: Grade,
): Promise<FlashcardStudy> {
  const csrfToken = await getCsrfToken();
  const res = await fetch(`${API_ROOT}/decks/${deckId}/study/review`, {
    method: "POST",
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      "X-XSRF-TOKEN": csrfToken,
    },
    body: JSON.stringify({ flashcardId, grade }),
  });
  if (!res.ok) {
    const data = await res
      .json()
      .catch(() => ({ message: "Failed to submit review" }));
    throw new ApiError(data.message, res.status);
  }
  return res.json();
}

export async function getAllDeckStats(): Promise<Record<number, DeckStats>> {
  const res = await fetch(`${API_ROOT}/decks/stats`, {
    credentials: "include",
  });
  if (!res.ok) {
    const data = await res
      .json()
      .catch(() => ({ message: "Failed to load stats" }));
    throw new ApiError(data.message, res.status);
  }
  return res.json();
}

export async function copyDeck(
  sourceDeckId: number,
  name: string,
): Promise<Deck> {
  const csrfToken = await getCsrfToken();
  const res = await fetch(`${API_ROOT}/decks/${sourceDeckId}/copy`, {
    method: "POST",
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      "X-XSRF-TOKEN": csrfToken,
    },
    body: JSON.stringify({ name }),
  });
  if (!res.ok) {
    const data = await res
      .json()
      .catch(() => ({ message: "Failed to copy deck" }));
    throw new ApiError(data.message, res.status);
  }
  return res.json();
}

export interface CreatedFlashcard {
  id: number;
  question: string;
  answer: string;
}

export async function createFlashcard(
  deckId: number,
  question: string,
  answer: string,
): Promise<CreatedFlashcard> {
  const csrfToken = await getCsrfToken();
  const res = await fetch(`${API_ROOT}/flashcards`, {
    method: "POST",
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      "X-XSRF-TOKEN": csrfToken,
    },
    body: JSON.stringify({ deckId, question, answer }),
  });
  if (!res.ok) {
    const data = await res
      .json()
      .catch(() => ({ message: "Failed to create flashcard" }));
    throw new ApiError(data.message, res.status);
  }
  return res.json();
}

export async function updateFlashcard(
  id: number,
  question: string,
  answer: string,
): Promise<Deck["flashcards"][0]> {
  const csrfToken = await getCsrfToken();
  const res = await fetch(`${API_ROOT}/flashcards/${id}`, {
    method: "PUT",
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      "X-XSRF-TOKEN": csrfToken,
    },
    body: JSON.stringify({ question, answer }),
  });
  if (!res.ok) {
    const data = await res
      .json()
      .catch(() => ({ message: "Failed to update flashcard" }));
    throw new ApiError(data.message, res.status);
  }
  return res.json();
}

export async function deleteFlashcard(id: number): Promise<void> {
  const csrfToken = await getCsrfToken();
  const res = await fetch(`${API_ROOT}/flashcards/${id}`, {
    method: "DELETE",
    credentials: "include",
    headers: {
      "X-XSRF-TOKEN": csrfToken,
    },
  });
  if (!res.ok) {
    const data = await res
      .json()
      .catch(() => ({ message: "Failed to delete flashcard" }));
    throw new ApiError(data.message, res.status);
  }
}

export async function attachFlashcardMedia(
  flashcardId: number,
  side: "question" | "answer",
  s3ObjectKey: string,
  fileName: string,
): Promise<void> {
  const csrfToken = await getCsrfToken();
  const res = await fetch(`${API_ROOT}/flashcards/${flashcardId}/media`, {
    method: "POST",
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      "X-XSRF-TOKEN": csrfToken,
    },
    body: JSON.stringify({ side, s3ObjectKey, fileName }),
  });
  if (!res.ok) {
    const data = await res
      .json()
      .catch(() => ({ message: "Failed to attach media" }));
    throw new ApiError(data.message, res.status);
  }
}

export async function deleteFlashcardMedia(
  id: number,
  side: "question" | "answer",
): Promise<void> {
  const csrfToken = await getCsrfToken();
  const res = await fetch(`${API_ROOT}/flashcards/${id}/media?side=${side}`, {
    method: "DELETE",
    credentials: "include",
    headers: {
      "X-XSRF-TOKEN": csrfToken,
    },
  });
  if (!res.ok) {
    const data = await res
      .json()
      .catch(() => ({ message: "Failed to delete media" }));
    throw new ApiError(data.message, res.status);
  }
}

// --- File upload validation ---

const MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
const ALLOWED_IMAGE_EXTENSIONS = [".jpg", ".jpeg", ".png", ".gif", ".webp"];
const ALLOWED_AUDIO_EXTENSIONS = [".mp3", ".wav", ".ogg"];

const EXTENSION_MIME_MAP: Record<string, string> = {
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".png": "image/png",
  ".gif": "image/gif",
  ".webp": "image/webp",
  ".mp3": "audio/mpeg",
  ".wav": "audio/wav",
  ".ogg": "audio/ogg",
};

export function validateMediaFile(file: File): string | null {
  if (file.size > MAX_FILE_SIZE) {
    return "File size exceeds 10MB limit.";
  }
  const ext = file.name.toLowerCase().substring(file.name.lastIndexOf("."));
  if (
    !ALLOWED_IMAGE_EXTENSIONS.includes(ext) &&
    !ALLOWED_AUDIO_EXTENSIONS.includes(ext)
  ) {
    return "Only image (.jpg, .png, .gif, .webp) and audio (.mp3, .wav, .ogg) files are allowed.";
  }
  return null;
}

// --- Presigned POST upload ---

export interface PresignedPostData {
  url: string;
  fields: Record<string, string>;
}

export async function getPresignedUploadData(
  flashcardId: number,
  fileName: string,
  isQuestion: boolean,
): Promise<PresignedPostData> {
  const params = new URLSearchParams({
    flashcardId: String(flashcardId),
    fileName,
    isQuestion: String(isQuestion),
  });
  const res = await fetch(`${API_ROOT}/s3/presigned-upload?${params}`, {
    credentials: "include",
  });
  if (!res.ok) {
    const data = await res
      .json()
      .catch(() => ({ message: "Failed to get upload URL" }));
    throw new ApiError(data.message, res.status);
  }
  return res.json();
}

export async function uploadFileToS3(
  presignedPost: PresignedPostData,
  file: File,
): Promise<void> {
  const formData = new FormData();
  // Add all presigned fields first
  for (const [key, value] of Object.entries(presignedPost.fields)) {
    formData.append(key, value);
  }
  // Override Content-Type with the actual file MIME type
  const ext = file.name.toLowerCase().substring(file.name.lastIndexOf("."));
  const mimeType = file.type || EXTENSION_MIME_MAP[ext] || "application/octet-stream";
  formData.set("Content-Type", mimeType);
  // File MUST be appended last — S3 requirement
  formData.append("file", file);

  const res = await fetch(presignedPost.url, {
    method: "POST",
    body: formData,
  });
  if (!res.ok) {
    throw new ApiError("Failed to upload media file", res.status);
  }
}
