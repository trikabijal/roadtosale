import type {
  LoginRequest,
  LoginResponse,
  AssignmentDTO,
  ChecksheetDTO,
  UserChecksheetCreateDTO,
  UserChecksheetDTO,
  UserChecksheetAnswerDTO,
  AddAssignmentRequest,
  TradePhotoSlot,
  TradePhotoDTO,
} from './types';

export interface ISmartComplyClient {
  // Auth
  login(req: LoginRequest): Promise<LoginResponse>;
  refreshToken(refreshToken: string): Promise<{ accessToken: string; refreshToken: string }>;
  logout(): Promise<void>;

  // Assignments / appointments
  getMyAssignments(): Promise<AssignmentDTO[]>;
  createWalkInAssignment(req: AddAssignmentRequest): Promise<{ count: number }>;

  // Checksheet (NADA template)
  getChecksheetDetail(checksheetId: number): Promise<ChecksheetDTO>;

  // Session lifecycle
  startOrResumeSession(dto: UserChecksheetCreateDTO): Promise<UserChecksheetDTO>;
  submitSession(userChecksheetId: number): Promise<UserChecksheetDTO>;

  // Cue detection answers
  submitAnswer(answer: UserChecksheetAnswerDTO): Promise<void>;
  submitAnswers(answers: UserChecksheetAnswerDTO[]): Promise<void>;

  // Trade-in photos
  uploadTradePhoto(
    userChecksheetId: number,
    slot: TradePhotoSlot,
    localUri: string,
    mimeType: string,
  ): Promise<TradePhotoDTO>;
  getTradePhotos(userChecksheetId: number): Promise<TradePhotoDTO[]>;
}
