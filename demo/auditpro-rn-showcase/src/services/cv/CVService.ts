export type BuyersOrderResult = {
  signaturePresent: boolean;
  lineItems: { label: string; value: string }[];
  capturedAt: Date;
};

export type VehicleConditionResult = {
  defects: string[];
  confidence: number;
};

export interface CVService {
  extractBuyersOrder(imageUri: string): Promise<BuyersOrderResult>;
  detectVehicleCondition(imageUri: string): Promise<VehicleConditionResult>;
}
