---
name: Clarity Flow
colors:
  surface: '#f9f9ff'
  surface-dim: '#cfdaf2'
  surface-bright: '#f9f9ff'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#f0f3ff'
  surface-container: '#e7eeff'
  surface-container-high: '#dee8ff'
  surface-container-highest: '#d8e3fb'
  on-surface: '#111c2d'
  on-surface-variant: '#3c4947'
  inverse-surface: '#263143'
  inverse-on-surface: '#ecf1ff'
  outline: '#6c7a77'
  outline-variant: '#bbcac6'
  surface-tint: '#006b5f'
  primary: '#006b5f'
  on-primary: '#ffffff'
  primary-container: '#14b8a6'
  on-primary-container: '#00423b'
  inverse-primary: '#4fdbc8'
  secondary: '#ae2f34'
  on-secondary: '#ffffff'
  secondary-container: '#ff6b6b'
  on-secondary-container: '#6d0010'
  tertiary: '#855300'
  on-tertiary: '#ffffff'
  tertiary-container: '#e49200'
  on-tertiary-container: '#543300'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#71f8e4'
  primary-fixed-dim: '#4fdbc8'
  on-primary-fixed: '#00201c'
  on-primary-fixed-variant: '#005048'
  secondary-fixed: '#ffdad8'
  secondary-fixed-dim: '#ffb3b0'
  on-secondary-fixed: '#410006'
  on-secondary-fixed-variant: '#8c1520'
  tertiary-fixed: '#ffddb8'
  tertiary-fixed-dim: '#ffb95f'
  on-tertiary-fixed: '#2a1700'
  on-tertiary-fixed-variant: '#653e00'
  background: '#f9f9ff'
  on-background: '#111c2d'
  surface-variant: '#d8e3fb'
typography:
  headline-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 32px
    fontWeight: '700'
    lineHeight: 40px
    letterSpacing: -0.02em
  headline-lg-mobile:
    fontFamily: Plus Jakarta Sans
    fontSize: 26px
    fontWeight: '700'
    lineHeight: 34px
    letterSpacing: -0.01em
  headline-md:
    fontFamily: Plus Jakarta Sans
    fontSize: 24px
    fontWeight: '600'
    lineHeight: 32px
    letterSpacing: -0.01em
  headline-sm:
    fontFamily: Plus Jakarta Sans
    fontSize: 18px
    fontWeight: '600'
    lineHeight: 26px
  body-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  body-md:
    fontFamily: Plus Jakarta Sans
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 22px
  body-sm:
    fontFamily: Plus Jakarta Sans
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 18px
  label-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 14px
    fontWeight: '600'
    lineHeight: 20px
  label-md:
    fontFamily: Plus Jakarta Sans
    fontSize: 12px
    fontWeight: '600'
    lineHeight: 16px
    letterSpacing: 0.02em
  code-lg:
    fontFamily: JetBrains Mono
    fontSize: 14px
    fontWeight: '500'
    lineHeight: 22px
  code-md:
    fontFamily: JetBrains Mono
    fontSize: 12px
    fontWeight: '500'
    lineHeight: 18px
  code-sm:
    fontFamily: JetBrains Mono
    fontSize: 11px
    fontWeight: '400'
    lineHeight: 16px
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  space-2xs: 0.25rem
  space-xs: 0.5rem
  space-sm: 0.75rem
  space-md: 1rem
  space-lg: 1.5rem
  space-xl: 2rem
  space-2xl: 3rem
  gutter-mobile: 1rem
  gutter-desktop: 1.5rem
  card-padding: 1.25rem
---

## Brand & Style

This design system reimagines log analysis and compliance administration away from cold, intimidating, developer-centric black terminal screens. Instead, it positions operational triage and review as an inviting, delightful, and human-centered consumer SaaS experience. 

The aesthetic marries tactile warmth, soft geometry, and luminous pastel accents with rigorous informational structure. It balances high-trust operational integrity with calm, approachable playfulness—minimizing cognitive fatigue during intensive log inspection sessions while maintaining rapid decision-making speed. Surfaces feel smooth, spacious, and tactile, leaning into pill-shaped triggers, airy card elevations, and cheerful semantic color coding.

## Colors

The color system delivers high visual comfort and optimistic clarity. The canvas avoids stark clinical whites in favor of an organic warm off-white canvas (`#F7F7F5`), while review panels sit on pristine `#FFFFFF` card surfaces.

### Functional Palette
- **Canvas / Root Background**: `#F7F7F5`
- **Surface Layer (Cards, Popovers)**: `#FFFFFF`
- **Inset Wells & Input Backdrops**: `#F1F1F1` (or `#F1F5F9` for data troughs)
- **Primary Action (Teal)**: `#14B8A6` (Hover: `#0D9488`, Active: `#0F766E`, Light Tint: `#CCFBF1`)
- **Destructive / Reject Action (Warm Coral)**: `#FF6B6B` (Hover: `#EE5253`, Tint: `#FFE4E6`)
- **Deep Neutral (Headings & Primary Text)**: `#1E293B`
- **Muted Neutral (Body, Subtitles, Meta)**: `#64748B`
- **Subtle Hairlines & Dividers**: `rgba(100, 116, 139, 0.12)`

### Playful Accent & Classification Tones
- **Mint (Verified / Optimal Log Status)**: `#6EE7B7` (Surface: `#ECFDF5`)
- **Sky Blue (Info / HTTP Route Ingestion)**: `#7DD3FC` (Surface: `#F0F9FF`)
- **Lavender (Trace Tokens / Context Tags)**: `#C4B5FD` (Surface: `#F5F3FF`)
- **Sunny Yellow (Warnings / Attention Flags)**: `#FDE047` (Surface: `#FEF9C3`)

## Typography

The type system blends the human, friendly warmth of **Plus Jakarta Sans** with the razor-sharp technical fidelity of **JetBrains Mono**.

- **Plus Jakarta Sans** handles all consumer-facing headings, body descriptions, controls, and micro-labels. Rounded geometry and generous x-height prevent dense review workflows from feeling stressful.
- **JetBrains Mono** is reserved strictly for operational values: JSON tokens, payload fragments, raw log keys, IP addresses, hashes, and request IDs. It brings rigorous monospaced order to complex technical data while harmonizing with the modern sans-serif.

## Layout & Spacing

This design system uses a fluid 12-column grid layout on desktop and tablet, collapsing to an adaptive single-column flow on mobile viewports.

### Breakpoints & Layout Rhythm
- **Desktop (1280px+)**: Master-detail workspace. A sticky 280px left navigation rail, a 420px queue list column, and an expansive flex inspector pane on the right.
- **Tablet (768px – 1279px)**: 8-column layout. Queue and inspector stack side-by-side or transition to a sliding drawer layout for deep log inspection.
- **Mobile (< 768px)**: 4-column flow with floating pill switchers fixed above the bottom safe area to seamlessly toggle between the triage list and detail view.

Gaps strictly adhere to an 8px base grid rhythm (4px, 8px, 12px, 16px, 24px, 32px, 48px). Cards and modules feature generous 20px–24px internal breathing room to reinforce the airy, approachable atmosphere.

## Elevation & Depth

Visual depth is achieved through ultra-soft, diffused ambient drop shadows and subtle tone shifts rather than hard dark borders. This creates a pillowy, tactile surface feel.

### Elevation Levels
- **Level 0 (Canvas Base)**: `#F7F7F5`. Flat, grounding surface with zero shadow.
- **Level 1 (Default Surface / Log Cards)**: `#FFFFFF` paired with an ambient shadow: `0 4px 20px -2px rgba(30, 41, 59, 0.05)`. Log rows and content panels float gracefully with borderless boundaries or a featherweight `1px solid rgba(100, 116, 139, 0.06)` outline.
- **Level 2 (Hover / Active Cards & Modals)**: Lifted card state featuring `0 12px 32px -4px rgba(30, 41, 59, 0.08), 0 4px 8px -2px rgba(30, 41, 59, 0.03)`.
- **Level 3 (Floating Pill Controls & Key Triggers)**: Pill buttons and active floating controls cast a softly saturated colored glow:
  - Primary Teal Glow: `0 6px 18px -3px rgba(20, 184, 166, 0.35)`
  - Destructive Coral Glow: `0 6px 18px -3px rgba(255, 107, 107, 0.35)`
- **Level Inset (Input Fields & Progress Wells)**: Depressed surfaces feature zero drop shadow and rely on solid `#F1F1F1` or subtle interior shading `inset 0 1px 2px rgba(0,0,0,0.04)`.

## Shapes

The shape system is rounded, tactile, and continuous. 

- **Primary Cards & Containers**: Feature smooth `18px` to `20px` corners, lending a relaxed, consumer-grade cadence to dense data displays.
- **Interactive Controls (Buttons, Inputs, Badges, Search Bars)**: Fully pill-shaped (`9999px` / `rounded-full`), softening the tactile affordances and encouraging easy scanning.
- **Data Wells & Code Blocks**: Rounded at `12px` to distinguish them from exterior card boundaries while preserving the friendly geometry.

## Components

### Buttons & Quick Actions
- **Primary Approve / Resolve**: Full pill-shaped (`rounded-full`), rich teal background (`#14B8A6`), pure white bold text, subtle teal aura shadow.
- **Secondary Reject / Flag**: Full pill-shaped, warm coral background (`#FF6B6B`), white bold text, subtle coral aura shadow.
- **Ghost / Neutral**: `#F1F1F1` fill, `#1E293B` text, turning to `#E2E8F0` on hover.

### Chips & Stat Pills
- Compact pill geometries containing bold numeric metrics and status tags.
- Stat pills pair cheerful pastel backdrops with deep saturated text (e.g., `#ECFDF5` background with `#0F766E` text for "99.8% Healthy").

### Queue List Cards
- Pristine `#FFFFFF` container cards with `18px` corner radii and Level 1 ambient elevation.
- Clear structural hierarchy: Monospaced ID chip on the top-left, severity badge top-right, followed by a bold Plus Jakarta Sans message title and truncated meta telemetry in muted slate below.
- Interactive states: On hover, cards smoothly elevate with a 4px vertical rise and display an action drawer containing one-click triage buttons.

### Inspector & Confidence Meter Bar
- Detailed inspection panel with an inset gray payload viewer (`JetBrains Mono`, `#F1F1F1` background, syntax-highlighted pastel keys).
- **Confidence Meter**: Inset horizontal trough (`height: 10px`, `rounded-full`, `#F1F5F9`) holding a vibrant gradient progress fill (Mint to Teal for high confidence, Sunny Yellow to Coral for low confidence or suspect anomalies).

### Form Inputs & Search Fields
- Fully rounded pill containers with an inset `#F1F1F1` background and placeholder text in `#94A3B8`.
- Focus states glow with a delicate 2px teal focus ring (`rgba(20, 184, 166, 0.30)`).

### Mobile Floating Switcher
- Floating pill-shaped segmented controller docked 16px above the viewport bottom with backdrop blur (`backdrop-blur-md`, `rgba(255, 255, 255, 0.85)`). Enables effortless thumb toggling between the review queue and the raw log inspector.