import type { Preview } from '@storybook/react-vite';

import { DEFAULT_LOCALE, messagesFor } from '../src/i18n';
import { LocaleContext } from '../src/i18n/useMessages';
import '../src/styles/theme.css';

// The components read their texts through the same provider the app mounts in main.tsx.
const preview: Preview = {
  decorators: [
    (Story) => (
      <LocaleContext value={{ locale: DEFAULT_LOCALE, messages: messagesFor(DEFAULT_LOCALE) }}>
        <div className="bg-bg p-6 text-text">
          <Story />
        </div>
      </LocaleContext>
    ),
  ],
  parameters: { layout: 'fullscreen', controls: { expanded: true } },
};

export default preview;
