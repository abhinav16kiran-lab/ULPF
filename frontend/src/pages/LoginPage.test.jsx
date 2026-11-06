import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import client from "../api/client";
import LoginPage from "./LoginPage";

vi.mock("../api/client", () => ({ default: { get: vi.fn(), post: vi.fn() } }));

function renderLogin(state) {
  render(
    <MemoryRouter initialEntries={[{ pathname: "/login", state }]}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/setup" element={<p>setup page</p>} />
        <Route path="/admin" element={<p>admin page</p>} />
      </Routes>
    </MemoryRouter>
  );
}

describe("LoginPage", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.mocked(client.get).mockReset();
    vi.mocked(client.post).mockReset();
  });

  it("sends the operator to setup while no super-admin exists", async () => {
    vi.mocked(client.get).mockResolvedValue({ data: { setupRequired: true } });
    renderLogin();
    expect(await screen.findByText("setup page")).toBeTruthy();
    expect(client.get).toHaveBeenCalledWith("/v1/setup/status");
  });

  it("pre-fills no credentials", async () => {
    vi.mocked(client.get).mockResolvedValue({ data: { setupRequired: false } });
    renderLogin();
    await waitFor(() => expect(client.get).toHaveBeenCalled());
    expect(screen.getByLabelText(/username/i).value).toBe("");
    expect(screen.getByLabelText(/^password$/i).value).toBe("");
  });

  it("remembers whether the admin is the super-admin", async () => {
    vi.mocked(client.get).mockResolvedValue({ data: { setupRequired: false } });
    vi.mocked(client.post).mockResolvedValue({
      data: { token: "t", username: "root_admin", role: "ADMIN", superAdmin: true },
    });
    renderLogin();
    fireEvent.change(screen.getByLabelText(/username/i), { target: { value: "root_admin" } });
    fireEvent.change(screen.getByLabelText(/^password$/i), { target: { value: "root-admin-pass-12" } });
    fireEvent.click(screen.getByRole("button", { name: /sign in/i }));

    expect(await screen.findByText("admin page")).toBeTruthy();
    expect(localStorage.getItem("superAdmin")).toBe("true");
  });

  it("keeps the falling-log showcase panel", async () => {
    vi.mocked(client.get).mockResolvedValue({ data: { setupRequired: false } });
    renderLogin();
    expect(screen.getByText("ULPF Real-Time Ingestion")).toBeTruthy();
  });

  it("shows the notice it was sent with", async () => {
    vi.mocked(client.get).mockResolvedValue({ data: { setupRequired: false } });
    renderLogin({ notice: "Super-admin created. Sign in with your new account." });
    expect(screen.getByText("Super-admin created. Sign in with your new account.")).toBeTruthy();
  });
});
