# AppleSTT Setup — Grant Microphone & Speech Recognition Access

macOS requires explicit user authorization for Speech Recognition and Microphone access. This is a one-time setup.

## Steps

1. **Open System Settings**
   - Click the Apple menu → System Settings

2. **Grant Microphone access to Terminal (or your shell)**
   - Go to: **Privacy & Security → Microphone**
   - Find "Terminal" (or bash/zsh if those show up)
   - Toggle the switch to **ON**

3. **Grant Speech Recognition access**
   - Go to: **Privacy & Security → Speech Recognition**
   - You should see a prompt asking to allow Terminal (or the Python process)
   - Click **OK** to grant access
   - If no prompt appears: find Terminal/Python and toggle the switch to **ON**

4. **Verify in System Settings**
   - Both Microphone and Speech Recognition should show Terminal as allowed

5. **Test the lab**
   ```bash
   cd voice-engine/lab
   source .venv/bin/activate
   voice-lab run --strategies apple_speech_transcriber \
     --scripts-dir fixtures/scripts \
     --audio-dir fixtures/synthesized \
     --reports-dir data/lab-reports
   ```

## Troubleshooting

- **Still getting exit code -5?**
  - Try running again; the prompt may need a second invocation to appear
  - Restart Terminal and try again
  - Check that the bundle identifier is correct (code-signed as com.auditpro.voiceengine.applestt)

- **Prompt keeps appearing?**
  - This is normal on first run; grant access and it should persist

- **System shows "Terminal doesn't have access"?**
  - Manually toggle the switch ON in System Settings for Speech Recognition
