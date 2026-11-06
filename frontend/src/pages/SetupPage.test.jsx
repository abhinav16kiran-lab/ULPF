import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { MemoryRouter, Routes, Route, useLocation } from "react-router-dom";
import * as api from "../api/admins";
import SetupPage from "./SetupPage";

vi.mock("../api/admins", async (importOriginal) => ({
  ...(await importOriginal()),
  getSetupStatus: vi.fn(),
  completeSetup: vi.fn(),
}));

function LoginStub() {
  return <p>login page: {useLocation().state?.notice}</p>;
}

function renderSetup() {
  render(
    <MemoryRouter initialEntries={["/setup"]}>
      <Routes>
        <Route path="/setup" element={<SetupPage />} />
        <Route path="/login" element={<LoginStub />} />
      </Routes>
    </MemoryRouter>
  );
}

const type = (label, value) => fireEvent.change(screen.getByLabelText(label), { target: { value } });

async function passStepOne() {
  type(/setup username/i, "setup");
  type(/setup password/i, "Xk3pQ9vR2m");
  fireEvent.click(screen.getByRole("button", { name: /continue/i }));
  await screen.findByRole("button", { name: /create super-admin/i });
}

describe("SetupPage", () => {
  beforeEach(() => {
    vi.mocked(api.getSetupStatus).mockReset().mockResolvedValue({ setupRequired: true });
    vi.mocked(api.completeSetup).mockReset();
  });

  it("asks for the setup credentials first, then the new account", async () => {
    renderSetup();
    await screen.findByLabelText(/setup username/i);
    expect(screen.queryByLabelText(/confirm password/i)).toBeNull();
    await passStepOne();
    expect(screen.getByLabelText(/confirm password/i)).toBeTruthy();
  });

  it("shows an inline error and sends nothing when the passwords differ", async () => {
    renderSetup();
    await screen.findByLabelText(/setup username/i);
    await passStepOne();
    type(/^username$/i, "root_admin");
    type(/^password$/i, "root-admin-pass-12");
    type(/confirm password/i, "something-else-12");
    fireEvent.click(screen.getByRole("button", { name: /create super-admin/i }));

    expect(screen.getByText("Passwords do not match")).toBeTruthy();
    expect(api.completeSetup).not.toHaveBeenCalled();
  });

  it("creates the super-admin and goes to login with a notice", async () => {
    vi.mocked(api.completeSetup).mockResolvedValue({ username: "root_admin" });
    renderSetup();
    await screen.findByLabelText(/setup username/i);
    await passStepOne();
    type(/^username$/i, "root_admin");
    type(/^password$/i, "root-admin-pass-12");
    type(/confirm password/i, "root-admin-pass-12");
    fireEvent.click(screen.getByRole("button", { name: /create super-admin/i }));

    expect(await screen.findByText(/login page: Super-admin created/)).toBeTruthy();
    expect(api.completeSetup).toHaveBeenCalledWith({
      setupUsername: "setup", setupPassword: "Xk3pQ9vR2m",
      username: "root_admin", password: "root-admin-pass-12", confirmPassword: "root-admin-pass-12",
    });
  });

  it("goes to login when setup is already done", async () => {
    vi.mocked(api.getSetupStatus).mockResolvedValue({ setupRequired: false });
    renderSetup();
    expect(await screen.findByText(/login page/)).toBeTruthy();
  });
});
