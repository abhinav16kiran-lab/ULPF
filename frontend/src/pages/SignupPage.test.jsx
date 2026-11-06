import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import SignupPage from "./SignupPage";

describe("SignupPage", () => {
  it("keeps the falling-log showcase panel", () => {
    render(<MemoryRouter><SignupPage /></MemoryRouter>);
    expect(screen.getByText("ULPF Real-Time Ingestion")).toBeTruthy();
  });
});
