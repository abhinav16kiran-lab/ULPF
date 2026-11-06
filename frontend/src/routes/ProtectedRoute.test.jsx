import { describe, it, expect, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import ProtectedRoute from "./ProtectedRoute";

function renderAt(path, allowedRoles) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/login" element={<p>login page</p>} />
        <Route path="/onboard" element={<p>onboarding page</p>} />
        <Route
          path="/admin"
          element={
            <ProtectedRoute allowedRoles={allowedRoles}>
              <p>admin page</p>
            </ProtectedRoute>
          }
        />
        <Route
          path="/admin/admins"
          element={
            <ProtectedRoute allowedRoles={["ADMIN"]} requireSuperAdmin>
              <p>admins page</p>
            </ProtectedRoute>
          }
        />
      </Routes>
    </MemoryRouter>
  );
}

describe("ProtectedRoute", () => {
  beforeEach(() => localStorage.clear());

  it("sends a visitor without a token to the login page", () => {
    renderAt("/admin", ["ADMIN"]);
    expect(screen.getByText("login page")).toBeTruthy();
  });

  it("sends a signed-in user without the required role to onboarding", () => {
    localStorage.setItem("token", "t");
    localStorage.setItem("role", "VENDOR");
    renderAt("/admin", ["ADMIN"]);
    expect(screen.getByText("onboarding page")).toBeTruthy();
  });

  it("shows the page to a user with the required role", () => {
    localStorage.setItem("token", "t");
    localStorage.setItem("role", "ADMIN");
    renderAt("/admin", ["ADMIN"]);
    expect(screen.getByText("admin page")).toBeTruthy();
  });

  it("sends a regular admin away from super-admin pages", () => {
    localStorage.setItem("token", "t");
    localStorage.setItem("role", "ADMIN");
    renderAt("/admin/admins");
    expect(screen.getByText("admin page")).toBeTruthy();
  });

  it("shows super-admin pages to the super-admin", () => {
    localStorage.setItem("token", "t");
    localStorage.setItem("role", "ADMIN");
    localStorage.setItem("superAdmin", "true");
    renderAt("/admin/admins");
    expect(screen.getByText("admins page")).toBeTruthy();
  });
});
