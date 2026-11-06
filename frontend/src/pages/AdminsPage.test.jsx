import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, fireEvent, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import * as api from "../api/admins";
import AdminsPage from "./AdminsPage";

vi.mock("../api/admins", async (importOriginal) => ({
  ...(await importOriginal()),
  listAdmins: vi.fn(),
  listPendingLinks: vi.fn(),
  createInvite: vi.fn(),
  createResetLink: vi.fn(),
  revokeAdmin: vi.fn(),
  cancelLink: vi.fn(),
}));

const ADMINS = [
  { userId: "r", username: "root_admin", superAdmin: true, createdAt: "2026-10-31T10:00:00" },
  { userId: "h", username: "helper", superAdmin: false, createdAt: "2026-11-01T10:00:00" },
];

function renderPage() {
  localStorage.setItem("token", "t");
  localStorage.setItem("role", "ADMIN");
  localStorage.setItem("superAdmin", "true");
  render(<MemoryRouter><AdminsPage /></MemoryRouter>);
}

describe("AdminsPage", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.mocked(api.listAdmins).mockReset().mockResolvedValue(ADMINS);
    vi.mocked(api.listPendingLinks).mockReset().mockResolvedValue([]);
    vi.mocked(api.createInvite).mockReset().mockResolvedValue({ linkId: "l1", kind: "INVITE", token: "inv-token", expiresAt: "2026-11-06T22:00:00Z" });
    vi.mocked(api.createResetLink).mockReset().mockResolvedValue({ linkId: "l2", kind: "RESET", token: "reset-token", expiresAt: "2026-11-06T22:00:00Z" });
    vi.mocked(api.revokeAdmin).mockReset().mockResolvedValue(undefined);
  });

  it("an invite shows the link once, with Copy", async () => {
    renderPage();
    await screen.findByText("helper");
    fireEvent.click(screen.getByRole("button", { name: /invite admin/i }));
    fireEvent.click(screen.getByRole("button", { name: /create link/i }));

    expect(await screen.findByDisplayValue(`${window.location.origin}/link/inv-token`)).toBeTruthy();
    expect(screen.getByRole("button", { name: /copy/i })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /done/i }));
    expect(screen.queryByDisplayValue(/inv-token/)).toBeNull();
  });

  it("a reset shows that admin's reset link once", async () => {
    renderPage();
    const row = (await screen.findByText("helper")).closest("li");
    fireEvent.click(within(row).getByRole("button", { name: /reset password/i }));

    expect(await screen.findByDisplayValue(`${window.location.origin}/link/reset-token`)).toBeTruthy();
    expect(api.createResetLink).toHaveBeenCalledWith("h");
  });

  it("revoke asks inline and calls the API only after confirming", async () => {
    renderPage();
    const row = (await screen.findByText("helper")).closest("li");
    fireEvent.click(within(row).getByRole("button", { name: /^revoke$/i }));
    expect(api.revokeAdmin).not.toHaveBeenCalled();

    fireEvent.click(within(row).getByRole("button", { name: /yes, revoke/i }));
    await vi.waitFor(() => expect(api.revokeAdmin).toHaveBeenCalledWith("h"));
  });

  it("the super-admin's row has no actions", async () => {
    renderPage();
    const row = (await screen.findByText("root_admin")).closest("li");
    expect(within(row).queryByRole("button")).toBeNull();
  });
});
