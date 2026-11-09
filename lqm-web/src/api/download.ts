import { ApiError, identityHeaders } from './client';

/**
 * Downloads a file from the API with the caller's identity. A plain `<a href download>` cannot: the browser sends no
 * `Authorization` header on a link, so with an identity provider the request would be refused.
 */
export async function downloadFile(path: string, fallbackName: string): Promise<void> {
  const response = await fetch(`/api/v1${path}`, { headers: identityHeaders() });
  if (!response.ok) {
    throw new ApiError(response.status, response.statusText);
  }
  const disposition = response.headers.get('Content-Disposition') ?? '';
  const name = /filename="?([^";]+)"?/.exec(disposition)?.[1] ?? fallbackName;
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  document.body.append(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}
