import {
  BuyersOrderResult,
  CVService,
  VehicleConditionResult,
} from './CVService';

export class ScriptedCVService implements CVService {
  async extractBuyersOrder(_imageUri: string): Promise<BuyersOrderResult> {
    await new Promise((r) => setTimeout(r, 350));
    return {
      signaturePresent: true,
      lineItems: [
        { label: 'Selling Price', value: '$41,250' },
        { label: 'Trade Allowance', value: '−$18,500' },
        { label: 'Tax + Fees', value: '$2,918' },
        { label: 'Total OTD', value: '$25,668' },
      ],
      capturedAt: new Date(),
    };
  }

  async detectVehicleCondition(_imageUri: string): Promise<VehicleConditionResult> {
    await new Promise((r) => setTimeout(r, 250));
    return {
      defects: [
        'Small dent — rear quarter panel',
        'Front tyres below 4mm — flagged for reconditioning',
      ],
      confidence: 0.92,
    };
  }
}
