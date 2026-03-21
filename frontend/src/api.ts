const API_BASE = "http://localhost:8080/api/v1/auth";

interface AuthResponse {
  message: string;
  username: string | null;
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
  password: string
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
  password: string
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
