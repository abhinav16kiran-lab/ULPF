import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import * as api from "../api/admins";
import LinkPage from "./LinkPage";

vi.mock("../api/admins", async (importOriginal) => ({
  ...(await importOriginal()),
  describeLink: vi.fn(),
  submitLink: vi.fn(),
}));

function renderLink() {
  render(
    <MemoryRouter initialEntries={["/link/tok123"]}>
      <Routes>
        <Route path="/link/:token" element={<LinkPage />} />
        <Route path="/login" element={<p>login page</p>} />
      </Routes>
    </MemoryRouter>
  );
}

const type = (label, value) => fireEvent.change(screen.getByLabelText(label), { target: { value } });

describe("LinkPage", () => {
  beforeEach(() => {
    vi.mocked(api.describeLink).mockReset();
    vi.mocked(api.submitLink).mockReset().mockResolvedValue(undefined);
  });

  it("shows a clear message and no form for an invalid link", async () => {
    vi.mocked(api.describeLink).mockRejectedValue({ response: { status: 410, data: { error: "gone" } } });
    renderLink();
    expect(await screen.findByText(/no longer valid/i)).toBeTruthy();
    expect(screen.queryByLabelText(/password/i)).toBeNull();
  });

  it("an invite asks for a username and password, then goes to login", async () => {
    vi.mocked(api.describeLink).mockResolvedValue({ kind: "INVITE", label: "Ashitha" });
    renderLink();
    await screen.findByLabelText(/^username$/i);
    type(/^username$/i, "ashitha");
    type(/^password$/i, "new-admin-pass-12");
    type(/confirm password/i, "new-admin-pass-12");
    fireEvent.click(screen.getByRole("button", { name: /create account/i }));

    expect(await screen.findByText("login page")).toBeTruthy();
    expect(api.submitLink).toHaveBeenCalledWith("tok123",
      { username: "ashitha", password: "new-admin-pass-12", confirmPassword: "new-admin-pass-12" });
  });

  it("shows the same falling-log showcase as login", async () => {
    vi.mocked(api.describeLink).mockResolvedValue({ kind: "INVITE" });
    renderLink();
    await screen.findByLabelText(/^username$/i);
    expect(screen.getByText("ULPF Real-Time Ingestion")).toBeTruthy();
  });

  it("a reset asks only for the new password", async () => {
    vi.mocked(api.describeLink).mockResolvedValue({ kind: "RESET", targetUsername: "helper" });
    renderLink();
    await screen.findByText(/@helper/);
    expect(screen.queryByLabelText(/^username$/i)).toBeNull();
    type(/^password$/i, "remembered-pass-12");
    type(/confirm password/i, "remembered-pass-12");
    fireEvent.click(screen.getByRole("button", { name: /set password/i }));

    expect(await screen.findByText("login page")).toBeTruthy();
  });
});
