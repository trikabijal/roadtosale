import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  SafeAreaView,
  ScrollView,
  Share,
  StatusBar,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import { getVoiceEngine } from './voice/NativeVoiceModule';
import type { VoiceEngineState } from './voice/IVoiceEngine';
import type { TranscriptEvent } from './voice/types';

interface Segment {
  id: number;
  text: string;
}

let _nextId = 0;

export default function JustTalkScreen() {
  const [segments, setSegments] = useState<Segment[]>([]);
  const [pendingText, setPendingText] = useState('');
  const [isListening, setIsListening] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const scrollRef = useRef<ScrollView>(null);

  useEffect(() => {
    const engine = getVoiceEngine();

    const unsubTranscript = engine.onTranscript((event: TranscriptEvent) => {
      if (event.stability === 'partial') {
        // Apple sends the full accumulated text with each partial — just replace
        setPendingText(event.text);
      } else {
        // Final result: commit to transcript, clear pending
        setSegments(prev => [...prev, { id: _nextId++, text: event.text }]);
        setPendingText('');
      }
    });

    const unsubState = engine.onStateChange((state: VoiceEngineState) => {
      setIsListening(state === 'listening');
      if (state === 'stopped' || state === 'idle') {
        setPendingText(prev => {
          if (prev.trim()) {
            setSegments(s => [...s, { id: _nextId++, text: prev.trim() }]);
          }
          return '';
        });
      }
    });

    const unsubError = engine.onError((err: Error) => {
      setError(err.message);
      setIsListening(false);
      setPendingText('');
    });

    return () => {
      unsubTranscript();
      unsubState();
      unsubError();
    };
  }, []);

  useEffect(() => {
    scrollRef.current?.scrollToEnd({ animated: true });
  }, [segments, pendingText]);

  const handleMicPress = useCallback(async () => {
    const engine = getVoiceEngine();
    setError(null);
    try {
      if (isListening) {
        await engine.stop();
      } else {
        const permission = await engine.requestPermissions();
        if (permission !== 'granted') {
          setError('Microphone permission denied. Go to Settings > Privacy > Microphone to enable it.');
          return;
        }
        await engine.start({ language: 'en-US' });
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unknown error starting microphone');
    }
  }, [isListening]);

  const handleShare = useCallback(async () => {
    const text = segments.map(s => s.text).join('\n\n');
    if (!text) return;
    try {
      await Share.share({ message: text });
    } catch {
      // user dismissed share sheet
    }
  }, [segments]);

  const hasContent = segments.length > 0 || pendingText.length > 0;

  return (
    <SafeAreaView style={styles.container}>
      <StatusBar barStyle="light-content" backgroundColor="#111" />

      <View style={styles.header}>
        <Text style={styles.title}>JustTalk</Text>
        <TouchableOpacity
          onPress={handleShare}
          disabled={segments.length === 0}
          style={[styles.shareBtn, segments.length === 0 && styles.shareBtnDisabled]}
          hitSlop={{ top: 12, bottom: 12, left: 12, right: 12 }}
        >
          <Text style={styles.shareBtnText}>Share</Text>
        </TouchableOpacity>
      </View>

      <ScrollView
        ref={scrollRef}
        style={styles.transcript}
        contentContainerStyle={styles.transcriptContent}
      >
        {!hasContent && (
          <Text style={styles.placeholder}>Tap the mic to start...</Text>
        )}
        {segments.map(seg => (
          <Text key={seg.id} style={styles.segment}>{seg.text}</Text>
        ))}
        {pendingText.length > 0 && (
          <Text style={styles.pending}>{pendingText}</Text>
        )}
      </ScrollView>

      {error != null && (
        <View style={styles.errorBanner}>
          <Text style={styles.errorText}>{error}</Text>
        </View>
      )}

      <View style={styles.controls}>
        <Text style={[styles.statusLabel, isListening && styles.statusLabelActive]}>
          {isListening ? 'Listening...' : 'Tap to start'}
        </Text>
        <TouchableOpacity
          style={[styles.micButton, isListening && styles.micButtonActive]}
          onPress={handleMicPress}
          activeOpacity={0.75}
        >
          <View style={[styles.micDot, isListening && styles.micDotActive]} />
        </TouchableOpacity>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#111',
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 20,
    paddingVertical: 14,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#2a2a2a',
  },
  title: {
    fontSize: 20,
    fontWeight: '700',
    color: '#fff',
    letterSpacing: -0.3,
  },
  shareBtn: {
    paddingHorizontal: 16,
    paddingVertical: 7,
    backgroundColor: '#2a2a2a',
    borderRadius: 20,
  },
  shareBtnDisabled: {
    opacity: 0.3,
  },
  shareBtnText: {
    color: '#fff',
    fontSize: 14,
    fontWeight: '600',
  },
  transcript: {
    flex: 1,
    paddingHorizontal: 20,
  },
  transcriptContent: {
    paddingTop: 20,
    paddingBottom: 32,
  },
  placeholder: {
    color: '#444',
    fontSize: 18,
    lineHeight: 28,
  },
  segment: {
    color: '#f0f0f0',
    fontSize: 18,
    lineHeight: 28,
    marginBottom: 12,
  },
  pending: {
    color: '#666',
    fontSize: 18,
    lineHeight: 28,
    fontStyle: 'italic',
  },
  errorBanner: {
    marginHorizontal: 16,
    marginBottom: 8,
    backgroundColor: '#3a1010',
    borderRadius: 10,
    padding: 14,
  },
  errorText: {
    color: '#ff6b6b',
    fontSize: 14,
    lineHeight: 20,
  },
  controls: {
    alignItems: 'center',
    paddingBottom: 40,
    paddingTop: 16,
  },
  statusLabel: {
    color: '#555',
    fontSize: 12,
    letterSpacing: 1,
    textTransform: 'uppercase',
    marginBottom: 14,
  },
  statusLabelActive: {
    color: '#e05555',
  },
  micButton: {
    width: 72,
    height: 72,
    borderRadius: 36,
    backgroundColor: '#2a2a2a',
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 2,
    borderColor: '#3a3a3a',
  },
  micButtonActive: {
    backgroundColor: '#2a0a0a',
    borderColor: '#cc3333',
  },
  micDot: {
    width: 28,
    height: 28,
    borderRadius: 14,
    backgroundColor: '#666',
  },
  micDotActive: {
    backgroundColor: '#ff4444',
  },
});
