/// <reference types="vite/client" />

declare const __UI_BUILD_TAG__: string;

interface ImportMetaEnv {
  readonly VITE_API_URL: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

