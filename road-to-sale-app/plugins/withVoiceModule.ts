/**
 * withVoiceModule.ts
 *
 * Expo config plugin that wires the native RtsVoiceModule into the iOS Xcode
 * project and the Android MainApplication after `expo prebuild`.
 *
 * iOS tasks:
 *   - Adds VoiceModule/RtsVoiceModule.swift and RtsVoiceModule.m to the main
 *     app target.
 *   - Sets the Swift Compiler Objective-C Bridging Header build setting to
 *     ios/VoiceModule/RtsVoiceModule-Bridging-Header.h.
 *
 * Android tasks:
 *   - Adds the RtsVoicePackage to the packages list in MainApplication.kt.
 *   - Adds the sherpa-onnx-android AAR dependency to build.gradle.
 *
 * Usage (app.json):
 *   "plugins": ["./plugins/withVoiceModule"]
 */

import {
  ConfigPlugin,
  withXcodeProject,
  withMainApplication,
  withAppBuildGradle,
} from 'expo/config-plugins';
import path from 'path';

// ── iOS ───────────────────────────────────────────────────────────────────────

const withVoiceModuleIos: ConfigPlugin = (config) => {
  return withXcodeProject(config, (config) => {
    const xcodeProject = config.modResults;
    // getApplicationNativeTarget requires projectName in some expo versions
    // Fall back to iterating targets manually for safety.
    const targets = xcodeProject.pbxNativeTargetSection();
    const appTarget = Object.values(targets).find(
      (t: any) => t?.productType === '"com.apple.product-type.application"',
    ) as any;
    const targetName: string | undefined = appTarget?.name?.replace(/^"|"$/g, '');

    if (!targetName) {
      console.warn('[withVoiceModule] Could not find Xcode app target — skipping iOS wiring.');
      return config;
    }

    const voiceModuleDir = path.join(config.modRequest.projectRoot, 'ios', 'VoiceModule');
    const files = [
      'RtsVoiceModule.swift',
      'RtsVoiceModule.m',
      'RtsVoiceModule-Bridging-Header.h',
    ];

    for (const file of files) {
      const filePath = path.join(voiceModuleDir, file);
      const groupName = 'VoiceModule';

      // Add group if it doesn't exist yet
      if (!xcodeProject.pbxGroupByName(groupName)) {
        const { uuid: groupUuid } = xcodeProject.addPbxGroup([], groupName, 'ios/VoiceModule');
        const mainGroup = xcodeProject.getFirstProject().firstProject.mainGroup;
        xcodeProject.addToPbxGroup({ uuid: groupUuid }, mainGroup);
      }

      // Skip if the file reference already exists
      const existingFile = xcodeProject.pbxFileReferenceSection();
      const alreadyAdded = Object.values(existingFile).some(
        (ref: any) => ref?.path === `VoiceModule/${file}`,
      );
      if (alreadyAdded) continue;

      xcodeProject.addSourceFile(filePath, { target: targetName }, groupName);
    }

    // Set bridging header
    const buildSettings = xcodeProject.getBuildProperty(
      'SWIFT_OBJC_BRIDGING_HEADER',
      'Release',
      targetName,
    );
    if (!buildSettings) {
      xcodeProject.addBuildProperty(
        'SWIFT_OBJC_BRIDGING_HEADER',
        '"$(SRCROOT)/VoiceModule/RtsVoiceModule-Bridging-Header.h"',
        'Release',
      );
      xcodeProject.addBuildProperty(
        'SWIFT_OBJC_BRIDGING_HEADER',
        '"$(SRCROOT)/VoiceModule/RtsVoiceModule-Bridging-Header.h"',
        'Debug',
      );
    }

    return config;
  });
};

// ── Android ──────────────────────────────────────────────────────────────────

const withVoiceModuleAndroid: ConfigPlugin = (config) => {
  // Step 1: Add sherpa-onnx dependency to app/build.gradle
  config = withAppBuildGradle(config, (config) => {
    const { contents } = config.modResults;
    const sherpaLine = "    implementation 'com.github.k2-fsa:sherpa-onnx-android:1.10.30'";
    const mavenLine = "        maven { url 'https://jitpack.io' }";

    if (!contents.includes('sherpa-onnx')) {
      config.modResults.contents = contents.replace(
        /dependencies\s*\{/,
        `dependencies {\n${sherpaLine}`,
      );
    }

    // Add JitPack to repositories if not already there
    if (!contents.includes('jitpack.io')) {
      config.modResults.contents = config.modResults.contents.replace(
        /allprojects\s*\{\s*repositories\s*\{/,
        `allprojects {\n    repositories {\n${mavenLine}`,
      );
    }

    return config;
  });

  // Step 2: Register RtsVoicePackage in MainApplication.kt
  config = withMainApplication(config, (config) => {
    const { contents } = config.modResults;
    const importLine = 'import com.trika.roadtosale.voice.RtsVoicePackage';
    const packageLine = '          packages.add(RtsVoicePackage())';

    let updated = contents;

    if (!updated.includes('RtsVoicePackage')) {
      // Add import after the last import statement
      updated = updated.replace(
        /(import com\.facebook\.react\.ReactApplication)/,
        `${importLine}\n$1`,
      );

      // Add to getPackages()
      updated = updated.replace(
        /(packages\.add\(new ReactPackage\(\)\))/,
        `$1\n${packageLine}`,
      );
      // Also handle the Kotlin style getPackages override
      updated = updated.replace(
        /(PackageList\(this\)\.packages)/,
        `PackageList(this).packages.also { it.add(RtsVoicePackage()) }`,
      );
    }

    config.modResults.contents = updated;
    return config;
  });

  return config;
};

// ── Combined plugin ───────────────────────────────────────────────────────────

const withVoiceModule: ConfigPlugin = (config) => {
  config = withVoiceModuleIos(config);
  config = withVoiceModuleAndroid(config);
  return config;
};

export default withVoiceModule;
