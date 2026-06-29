#import "RtsVoiceModule.h"
#import <Speech/Speech.h>
#import <AVFoundation/AVFoundation.h>

@implementation RtsVoiceModule {
  AVAudioEngine *_audioEngine;
  SFSpeechAudioBufferRecognitionRequest *_recognitionRequest;
  SFSpeechRecognitionTask *_recognitionTask;
  SFSpeechRecognizer *_speechRecognizer;
  BOOL _isMuted;
  BOOL _isStopping;
  long long _sessionStartMs;
}

RCT_EXPORT_MODULE()

+ (BOOL)requiresMainQueueSetup { return NO; }

- (NSArray<NSString *> *)supportedEvents {
  return @[@"onTranscriptEvent", @"onVoiceStateChange", @"onVoiceError"];
}

RCT_EXPORT_METHOD(requestPermissions:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
  [SFSpeechRecognizer requestAuthorization:^(SFSpeechRecognizerAuthorizationStatus authStatus) {
    [[AVAudioSession sharedInstance] requestRecordPermission:^(BOOL granted) {
      dispatch_async(dispatch_get_main_queue(), ^{
        if (authStatus == SFSpeechRecognizerAuthorizationStatusAuthorized && granted) {
          resolve(@"granted");
        } else if (authStatus == SFSpeechRecognizerAuthorizationStatusDenied ||
                   authStatus == SFSpeechRecognizerAuthorizationStatusRestricted) {
          resolve(@"denied");
        } else {
          resolve(@"undetermined");
        }
      });
    }];
  }];
}

RCT_EXPORT_METHOD(startListening:(NSString *)language
                  customVocabulary:(NSArray<NSString *> *)customVocabulary
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
  NSLocale *locale = [NSLocale localeWithLocaleIdentifier:language];
  _speechRecognizer = [[SFSpeechRecognizer alloc] initWithLocale:locale];

  if (!_speechRecognizer || !_speechRecognizer.isAvailable) {
    reject(@"unavailable", [NSString stringWithFormat:@"Speech recognizer not available for locale %@", language], nil);
    return;
  }

  NSError *error = nil;
  AVAudioSession *session = [AVAudioSession sharedInstance];
  [session setCategory:AVAudioSessionCategoryPlayAndRecord
                  mode:AVAudioSessionModeMeasurement
               options:AVAudioSessionCategoryOptionDefaultToSpeaker |
                       AVAudioSessionCategoryOptionMixWithOthers
                 error:&error];
  if (error) { reject(@"audio_error", error.localizedDescription, error); return; }

  [session setActive:YES withOptions:AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation error:&error];
  if (error) { reject(@"audio_error", error.localizedDescription, error); return; }

  _audioEngine = [[AVAudioEngine alloc] init];
  _recognitionRequest = [[SFSpeechAudioBufferRecognitionRequest alloc] init];
  _recognitionRequest.shouldReportPartialResults = YES;
  if (customVocabulary.count > 0) _recognitionRequest.contextualStrings = customVocabulary;

  _sessionStartMs = (long long)([[NSDate date] timeIntervalSince1970] * 1000);
  _isMuted = NO;
  _isStopping = NO;

  __weak typeof(self) weakSelf = self;
  _recognitionTask = [_speechRecognizer recognitionTaskWithRequest:_recognitionRequest
    resultHandler:^(SFSpeechRecognitionResult *result, NSError *taskError) {
      __strong typeof(weakSelf) strongSelf = weakSelf;
      if (!strongSelf) return;
      if (taskError) {
        if (!strongSelf->_isStopping) {
          [strongSelf sendEventWithName:@"onVoiceError" body:taskError.localizedDescription];
        }
        return;
      }
      if (!result) return;

      long long nowMs = (long long)([[NSDate date] timeIntervalSince1970] * 1000);
      long long latency = nowMs - strongSelf->_sessionStartMs;
      NSString *text = result.bestTranscription.formattedString;
      NSString *stability = result.isFinal ? @"final" : @"partial";

      id confidence = [NSNull null];
      if (result.isFinal && result.bestTranscription.segments.lastObject) {
        confidence = @(result.bestTranscription.segments.lastObject.confidence);
      }

      [strongSelf sendEventWithName:@"onTranscriptEvent" body:@{
        @"text": text,
        @"stability": stability,
        @"timestamp_ms": @(nowMs),
        @"latency_ms_from_audio_start": @(latency),
        @"confidence": confidence,
        @"engine_metadata": @{@"engine": @"apple_speech_transcriber", @"is_final": @(result.isFinal)}
      }];
    }];

  AVAudioInputNode *inputNode = _audioEngine.inputNode;
  AVAudioFormat *format = [inputNode outputFormatForBus:0];
  [inputNode installTapOnBus:0 bufferSize:1024 format:format block:^(AVAudioPCMBuffer *buf, AVAudioTime *when) {
    __strong typeof(weakSelf) strongSelf = weakSelf;
    if (!strongSelf || strongSelf->_isMuted) return;
    [strongSelf->_recognitionRequest appendAudioPCMBuffer:buf];
  }];

  [_audioEngine prepare];
  NSError *startError = nil;
  [_audioEngine startAndReturnError:&startError];
  if (startError) { reject(@"start_error", startError.localizedDescription, startError); return; }

  [self sendEventWithName:@"onVoiceStateChange" body:@"listening"];
  resolve(nil);
}

RCT_EXPORT_METHOD(stopListening:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
  _isStopping = YES;
  [_audioEngine stop];
  [_audioEngine.inputNode removeTapOnBus:0];
  [_recognitionRequest endAudio];
  [_recognitionTask cancel];
  _recognitionRequest = nil;
  _recognitionTask = nil;
  [self sendEventWithName:@"onVoiceStateChange" body:@"stopped"];
  resolve(nil);
}

RCT_EXPORT_METHOD(mute) {
  _isMuted = YES;
  [self sendEventWithName:@"onVoiceStateChange" body:@"muted"];
}

RCT_EXPORT_METHOD(unmute) {
  _isMuted = NO;
  [self sendEventWithName:@"onVoiceStateChange" body:@"listening"];
}

@end
