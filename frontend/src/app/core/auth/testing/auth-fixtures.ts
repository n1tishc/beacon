import { Role, SessionResponse, User } from '../auth.model';

export function aUser(role: Role = 'RESPONDER'): User {
  return {
    id: crypto.randomUUID(),
    username: role.toLowerCase(),
    displayName: `Test ${role}`,
    role,
  };
}

export function aSession(
  accessToken = 'access-token-1',
  role: Role = 'RESPONDER',
): SessionResponse {
  return { accessToken, tokenType: 'Bearer', expiresIn: 900, user: aUser(role) };
}
