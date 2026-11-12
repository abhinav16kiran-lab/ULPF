import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, fireEvent, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import client from "../api/client";
import * as onboardingApi from "../api/onboarding";
import OnboardingPage from "./OnboardingPage";

vi.mock("../api/client", () => ({ default: { get: vi.fn(), post: vi.fn() } }));
vi.mock("../api/onboarding", () => ({ createApiKey: vi.fn() }));

const KEY = "ulpf_live_0123456789abcdef0123456789abcdef01234567";
const SOURCE_WITHOUT_KEY = {
  sourceId: "s1", vendorId: "v1", sourceName: "Firewall Alpha", sourceType: "SYSLOG",
  status: "ACTIVE", createdAt: "2026-11-01T09:00:00", apiKeyCreatedAt: null,
};
const SOURCE_WITH_KEY = { ...SOURCE_WITHOUT_KEY, apiKeyCreatedAt: "2026-11-12T10:42:00" };

function serve(sources) {
  vi.mocked(client.get).mockImplementation((url) => {
    if (url === "/v1/onboard/my-sources") return Promise.resolve({ data: { sources } });
    if (url === "/v1/onboard/my-requests") return Promise.resolve({ data: { requests: [] } });
    return Promise.resolve({ data: {} });
  });
}

async function openActiveSources() {
  render(<MemoryRouter><OnboardingPage /></MemoryRouter>);
  fireEvent.click(screen.getByRole("button", { name: "My Active Log Sources" }));
  return screen.findByText("Firewall Alpha");
}

describe("OnboardingPage API keys", () => {
  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem("token", "t");
    localStorage.setItem("username", "alice");
    localStorage.setItem("role", "VENDOR");
    vi.mocked(client.get).mockReset();
    vi.mocked(client.post).mockReset();
    vi.mocked(onboardingApi.createApiKey).mockReset().mockResolvedValue({
      sourceId: "s1", apiKey: KEY, createdAt: "2026-11-12T10:42:00", replacedPreviousKey: false,
    });
  });

  it("after a new submit, shows no key and says to create it after approval", async () => {
    serve([]);
    vi.mocked(client.post).mockResolvedValue({
      data: { requestId: "req-1", sourceId: "s1", vendorId: "v1", status: "SUBMITTED", message: "Submitted." },
    });
    render(<MemoryRouter><OnboardingPage /></MemoryRouter>);

    fireEvent.change(screen.getByLabelText(/Vendor Name/), { target: { value: "Alice Corp" } });
    fireEvent.change(screen.getByLabelText(/Log Source Name/), { target: { value: "Firewall Alpha" } });
    fireEvent.click(screen.getByRole("button", { name: "SYSLOG" }));
    fireEvent.click(screen.getByRole("button", { name: /Submit Onboarding Request/ }));

    expect(await screen.findByText("Request Submitted!")).toBeTruthy();
    expect(screen.getByText(/create your API key/i)).toBeTruthy();
    expect(screen.queryByText(/Save this API key/i)).toBeNull();
    expect(screen.queryByText(/Active API Key Preserved/i)).toBeNull();
  });

  it("an active source without a key offers Create API key and shows the new key once", async () => {
    serve([SOURCE_WITHOUT_KEY]);
    await openActiveSources();

    fireEvent.click(screen.getByRole("button", { name: "Create API key" }));

    expect(await screen.findByText(KEY)).toBeTruthy();
    expect(onboardingApi.createApiKey).toHaveBeenCalledWith("s1", false);
    expect(screen.getByText(/cannot see it again/i)).toBeTruthy();
  });

  it("Regenerate asks first, and Cancel sends nothing", async () => {
    serve([SOURCE_WITH_KEY]);
    await openActiveSources();

    fireEvent.click(screen.getByRole("button", { name: "Regenerate" }));
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText(/stops working now/i)).toBeTruthy();
    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));

    expect(screen.queryByRole("dialog")).toBeNull();
    expect(onboardingApi.createApiKey).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Regenerate" }));
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Regenerate key" }));

    expect(await screen.findByText(KEY)).toBeTruthy();
    expect(onboardingApi.createApiKey).toHaveBeenCalledWith("s1", true);
  });

  it("closing the key box removes the key from the page", async () => {
    serve([SOURCE_WITHOUT_KEY]);
    await openActiveSources();
    fireEvent.click(screen.getByRole("button", { name: "Create API key" }));
    await screen.findByText(KEY);

    fireEvent.click(screen.getByRole("button", { name: "I have saved the key" }));

    expect(screen.queryByText(KEY)).toBeNull();
  });
});
