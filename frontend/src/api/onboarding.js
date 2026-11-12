import client from "./client";

/**
 * Vendor onboarding API for the key of an approved log source.
 * createApiKey creates the first key, or replaces the current key when replaceExisting is true.
 * The response holds the raw key once: { sourceId, apiKey, createdAt, replacedPreviousKey }.
 * Refusals carry { error, code }, with code NOT_OWNER, SOURCE_NOT_FOUND, SOURCE_NOT_ACTIVE or KEY_EXISTS.
 */
export const createApiKey = (sourceId, replaceExisting = false) =>
  client
    .post(`/v1/onboard/sources/${encodeURIComponent(sourceId)}/api-key`, { replaceExisting })
    .then((r) => r.data);
