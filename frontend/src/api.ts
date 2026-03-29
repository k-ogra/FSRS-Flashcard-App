// TODO: Change to actual backend URL (env var?)
const API_ROOT = "http://localhost:8080/api/v0";
const API_BASE = `${API_ROOT}/auth`;

export interface AuthResponse {
  message: string;
  username: string | null;
}

export interface Deck {
  id: number;
  name: string;
  isPublic: boolean;
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
  console.log("TOGGLING");
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

export async function getNewQueue(deckId: number): Promise<FlashcardStudy[]> {
  const res = await fetch(`${API_ROOT}/decks/${deckId}/study/new`, {
    credentials: "include",
  });
  if (!res.ok) {
    const data = await res
      .json()
      .catch(() => ({ message: "Failed to load new queue" }));
    throw new ApiError(data.message, res.status);
  }
  return res.json();
}

export async function getLearningQueue(
  deckId: number,
  aheadMinutes?: number,
): Promise<FlashcardStudy[]> {
  const params = aheadMinutes != null ? `?aheadMinutes=${aheadMinutes}` : "";
  const res = await fetch(`${API_ROOT}/decks/${deckId}/study/learning${params}`, {
    credentials: "include",
  });
  if (!res.ok) {
    const data = await res
      .json()
      .catch(() => ({ message: "Failed to load learning queue" }));
    throw new ApiError(data.message, res.status);
  }
  return res.json();
}

export async function getReviewQueue(
  deckId: number,
  aheadMinutes?: number,
): Promise<FlashcardStudy[]> {
  const params = aheadMinutes != null ? `?aheadMinutes=${aheadMinutes}` : "";
  const res = await fetch(`${API_ROOT}/decks/${deckId}/study/review${params}`, {
    credentials: "include",
  });
  if (!res.ok) {
    const data = await res
      .json()
      .catch(() => ({ message: "Failed to load review queue" }));
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

export async function createFlashcard(
  deckId: number,
  question: string,
  answer: string,
): Promise<void> {
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
}
