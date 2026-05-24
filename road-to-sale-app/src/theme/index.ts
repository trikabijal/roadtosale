import { useColorScheme } from 'react-native';
import { DarkColors, LightColors, ColorScheme } from './colors';
export { Typography } from './typography';
export type { ColorScheme };

export function useTheme(): { colors: ColorScheme; isDark: boolean } {
  const scheme = useColorScheme();
  const isDark = scheme === 'dark';
  return { colors: isDark ? DarkColors : LightColors, isDark };
}
