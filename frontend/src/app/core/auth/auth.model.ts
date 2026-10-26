export type Role = 'ADMIN' | 'RESPONDER' | 'VIEWER';

export interface User {
  id: string;
  username: string;
  displayName: string;
  role: Role;
}

/** Body of /api/auth/login and /api/auth/refresh. The refresh token itself is an HttpOnly cookie. */
export interface SessionResponse {
  accessToken: string;
  tokenType: 'Bearer';
  expiresIn: number;
  user: User;
}
