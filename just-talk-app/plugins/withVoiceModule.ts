import { ConfigPlugin, withXcodeProject, withDangerousMod } from 'expo/config-plugins';
import fs from 'fs';
import path from 'path';

const SOURCE_FILES = ['RtsVoiceModule.m'];
const HEADER_FILES = ['RtsVoiceModule.h'];

const withVoiceModule: ConfigPlugin = (config) => {
  config = withDangerousMod(config, [
    'ios',
    (config) => {
      const podfilePath = path.join(config.modRequest.projectRoot, 'ios', 'Podfile');
      if (fs.existsSync(podfilePath)) {
        let podfile = fs.readFileSync(podfilePath, 'utf-8');
        if (!podfile.includes('new_arch_enabled')) {
          podfile = podfile.replace(
            'use_react_native!(',
            'use_react_native!(\n    :new_arch_enabled => false,',
          );
          fs.writeFileSync(podfilePath, podfile, 'utf-8');
        }
      }
      return config;
    },
  ]);

  return withXcodeProject(config, (config) => {
    const projectRoot = config.modRequest.projectRoot;

    const srcDir = path.join(projectRoot, 'native', 'VoiceModule');
    const dstDir = path.join(projectRoot, 'ios', 'VoiceModule');
    fs.mkdirSync(dstDir, { recursive: true });
    for (const file of [...SOURCE_FILES, ...HEADER_FILES]) {
      fs.copyFileSync(path.join(srcDir, file), path.join(dstDir, file));
    }

    const xcodeProject = config.modResults;

    const targets = xcodeProject.pbxNativeTargetSection();
    const appTarget = Object.values(targets).find(
      (t: any) => t?.productType === '"com.apple.product-type.application"',
    ) as any;
    const targetName: string | undefined = appTarget?.name?.replace(/^"|"$/g, '');

    if (!targetName) {
      console.warn('[withVoiceModule] Could not find Xcode app target — skipping iOS wiring.');
      return config;
    }

    const targetUuid: string | null = xcodeProject.findTargetKey(targetName);
    if (!targetUuid) {
      console.warn(`[withVoiceModule] Could not find target UUID for "${targetName}" — skipping.`);
      return config;
    }

    const groupName = 'VoiceModule';
    let groupUuid: string;
    if (xcodeProject.pbxGroupByName(groupName)) {
      const pbxGroups = xcodeProject.hash.project.objects['PBXGroup'] as Record<string, any>;
      groupUuid = Object.keys(pbxGroups).find(
        key => !key.endsWith('_comment') && pbxGroups[key]?.name === groupName,
      ) ?? '';
    } else {
      const result = xcodeProject.addPbxGroup([], groupName, 'VoiceModule');
      groupUuid = result.uuid;
      const mainGroup = xcodeProject.getFirstProject().firstProject.mainGroup;
      xcodeProject.addToPbxGroup({ uuid: groupUuid }, mainGroup);
    }

    const fileRefs = xcodeProject.pbxFileReferenceSection() as Record<string, any>;

    for (const file of SOURCE_FILES) {
      const relPath = `VoiceModule/${file}`;
      const alreadyAdded = Object.values(fileRefs).some(
        (ref) => ref?.path?.replace(/^"|"$/g, '') === relPath,
      );
      if (alreadyAdded) continue;
      xcodeProject.addSourceFile(relPath, { target: targetUuid }, groupUuid);
    }

    for (const file of HEADER_FILES) {
      const relPath = `VoiceModule/${file}`;
      const alreadyAdded = Object.values(fileRefs).some(
        (ref) => ref?.path?.replace(/^"|"$/g, '') === relPath,
      );
      if (alreadyAdded) continue;
      xcodeProject.addHeaderFile(relPath, { target: targetUuid }, groupUuid);
    }

    return config;
  });
};

export default withVoiceModule;
