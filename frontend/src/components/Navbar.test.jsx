import { describe, it, expect, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import Navbar from "./Navbar";

function renderAs(role, superAdmin) {
  localStorage.setItem("token", "t");
  localStorage.setItem("role", role);
  if (superAdmin) localStorage.setItem("superAdmin", "true");
  render(<MemoryRouter><Navbar /></MemoryRouter>);
}

describe("Navbar", () => {
  beforeEach(() => localStorage.clear());

  it("shows Admins to the super-admin", () => {
    renderAs("ADMIN", true);
    expect(screen.getByRole("link", { name: "Admins" })).toBeTruthy();
  });

  it("hides Admins from a regular admin", () => {
    renderAs("ADMIN", false);
    expect(screen.queryByRole("link", { name: "Admins" })).toBeNull();
  });
});
