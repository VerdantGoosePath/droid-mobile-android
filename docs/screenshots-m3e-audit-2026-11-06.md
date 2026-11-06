# Droid Mobile — Screenshot-by-screenshot Material 3 Expressive audit

Review date: **2026-11-06**. Evidence: the 21 user-provided 1344 × 2992 screenshots, captured 09:21–09:24. Reviewed against the Compose branch as it existed before this design iteration.

**Scope:** Observations refer to the *provided APK*, not claims about unseen screens. A single static capture cannot establish animation timing, accessibility focus order, TalkBack behavior, screen rotation, or interaction fidelity. Those must be verified separately. “M3E violation” below identifies a concrete discrepancy with Material guidance or a failure to use the system coherently; not every possible aesthetic choice is a normative Material prohibition.

## Systemic problems and design causes

| ID | Evidence | Cause | Corrective system |
|---|---|---|---|
| S01 — critical | Headings, back arrows, actions and notification icons occupy the same pixels on screenshots 02–20 | Individual screen rows start at y=0 without an inset-aware top app bar, despite the Activity enabling edge-to-edge | A single Compose `Scaffold` with Material 3 top app bars, system insets and central navigation ownership. Root screens use `MediumFlexibleTopAppBar`; details use `TopAppBar` |
| S02 — critical | The onboarding title and explanatory text are black on a near-black background (00–01) | No root Material `Surface`, so the default `LocalContentColor` does not correctly follow `onSurface` | Root `Surface(colorScheme.surface)`, explicit tonal pairing for all card labels, use `onSurfaceVariant` for supporting text |
| S03 — high | Huge, homogeneous black areas and tiny isolated controls (07, 13, 16–20) | Screens implemented as straight `Column` dumps, without empty/loading/error patterns, structured sections or intentional flexible placement | Semantic empty, loading and error states; grouped surface sections; whitespace *inside* a composition rather than abandoned screen area |
| S04 — high | JSON blobs presented to users (08, 11) and terminal/network objects used as text | Backend data printed with `toString(2)` without view models / presentation mapping | Typed/presentational summaries and actionable error descriptions; optional expandable technical details |
| S05 — high | Every item is a raised card or standalone chip (02, 09, 12, 14) | No visual grammar for related settings/list items vs truly independent content | Group related rows in a shared `surfaceContainerLow`; use expressive `ListItem` patterns with dividers, leading icons, supporting copy and trailing affordances |
| S06 — high | No context-sensitive emphasis: destructive actions look like cancellation; primary tasks are generic small controls (04, 11, 15) | Component selection by convenience instead of intent | Single strong primary action, tonal secondary actions, explicit destructive confirmation, status-specific disabling and clearly labelled affordances |
| S07 — high | Unnamed spinning indicator, blank tool bubbles, unlabelled empty MCP screen | No durable loading, empty or failure state model | M3E `LoadingIndicator` with task label; compact events, stateful empty views and retry |
| S08 — medium | Chip rows used for mutually exclusive mode, theme, tab and language choices | Local code treats all selection as `FilterChip` regardless of semantics | Tabs for screen destinations; segmented buttons for short single-choice sets; radio choices for longer preference lists; chips for filters |
| S09 — medium | Dynamic Material You toggle and theme selection did not change palette immediately | SharedPreferences written without Compose observing them | Theme preference listener in central `DroidTheme`; single colour-role system that reacts to changes |
| S10 — high | UI labels for important actions omit context or status; terminal ID must be typed manually; Git actions remain enabled for non-Git directories | Backend events are not reduced to task-oriented frontend state | Dedicated repository state for terminal output/status, shell picker and generated IDs, capability-gated commands |
| S11 — medium | Mobile uses same arrangements at all widths and font scales | Only bottom navigation was adaptive; page content was not constrained | Max-readable width, adaptive nav suite, FlowRow for variable-length options; verify font scale 200% and small devices |
| S12 — medium | The frontend hard-codes English and lacks coherent content tone across screens | Ad-hoc labels instead of resources and feature language system | Externalize string resources, Android app-language support, content descriptions and consistent naming; not yet complete |
| S13 — high | Information/security claims and status sometimes contradicted actual implementation | Placeholder copy was treated as a final UI feature | Do not expose unsupported functionality as functional; describe relay E2EE, biometrics and background service limitations plainly |
| S14 — medium | No visible expressive motion beyond defaults | Manual screens use static `Column` and raw control stacks | Use `MaterialTheme.motionScheme` and M3E components' built-in spring/shape interactions; add purposeful content-size/selection transitions, respect accessibility motion settings |
| S15 — medium | No consistent spacing or type roles between lists, inputs, app bars and sections | Each feature used arbitrary `padding(12/16/18/20)`, local bold titles | One `DroidTokens` spacing scale, complete Material type role scale, app bar hierarchy and grouped section primitives |

## Screenshot-specific audit and redesign

### 00 — 09:21:59 — Remote Control initial form
- **Observed:** “Droid Mobile” and description practically disappear into dark background (S02). Two stacked hero titles compete, with unnecessary copy about Tailscale. Giant card combines authentication, computer discovery, manual endpoint and status without step hierarchy. API key consumes long undifferentiated line. Region chips convey no context. Security warning is tiny and detached.
- **M3E direction:** top app bar + one confident `headlineLarge` welcome; tonal grouped connection form; label account region with single-choice segmented button; one primary “Find computers” action, manual URL as secondary method; secure/status supporting elements; warning in distinct semantic tertiary surface. Centralised colour/shape/typography, not local hexes.
- **Applied:** Surface/contrast, consistent headline, segmented region choice, StatusPill, warning surface. **Outstanding:** guided credential/device-code login; token visibility toggle; input-specific validation.

### 01 — 09:22:06 — Discovered computers
- **Observed:** active PC entries look identical to generic dark blocks, without a device icon, relay status icon or unambiguous “Connect” indication. Status repeats as a small unadorned word. Height is driven by arbitrary padding rather than list design.
- **M3E direction:** selectable grouped `ListItem` rows with computer icon, machine name, status and trailing action; clear progress and error feedback during discovery.
- **Applied:** computer entries use grouped list actions; state label inside supporting text. **Outstanding:** online/offline affordance and last-seen information once API provides reliable fields.

### 02 — 09:22:15 — Session list
- **Observed:** status-bar collision affects title, refresh and create icons (S01). Existing session has two redundant actions (Open + card click), generic Manage, technical file path and generic card elevation; near-empty screen.
- **M3E direction:** flexible top app bar with supporting count; a restrained session row with title, workspace label and status pill; search and one prominent create action; purpose-built empty state.
- **Applied:** central top app bar, conversational count grammar, status pill, tonal cards, empty state. **Outstanding:** richer session previews, recency, pin/group/archive management, search behavior at scale.

### 03 — 09:22:24 — New session bottom sheet
- **Observed:** same header collision behind the modal; form is one flat stack. Mode and autonomy choices are indiscriminate little chips, and “Use Git worktree” floats disconnected. Button disabled state is unclear. The sheet lacks contextual explanation of defaults.
- **M3E direction:** Material `ModalBottomSheet`, grouped labelled form sections; segmented mode selection, responsive autonomy filter group, worktree as a setting row, full-width primary action and contextual validation.
- **Applied:** segmented interaction modes and FlowRow autonomy. **Outstanding:** directory picker and trust confirmation, keyboard/IME and a11y checks, action error in sheet.

### 04 — 09:22:29 — Manage session dialog
- **Observed:** “Archive” occupies the position normally reserved for cancelling, with no dedicated Cancel; a destructive click can be mistaken for dismissal. No destructive confirmation.
- **M3E direction:** clear dialog purpose, editable field, distinct “Save name” vs “Cancel”; archive requires explicit confirmation and can use lower emphasis or error tint.
- **Applied:** Cancel added, archive gets separate confirmation. **Outstanding:** richer management menu instead of overloaded dialog.

### 05–06 — 09:22:36 and 09:22:42 — Chat transcript
- **Observed:** title, back arrow and id overlap status icons. Multiple empty “Tool” or “Assistant” cards consume 60–80dp each without displaying anything, drowning valuable text. Composer is visually small and terminal-like; no Markdown/code, activity icons, message time, tool type or streaming affordance.
- **M3E direction:** compact, expandable tool events; distinguish user and agent by tonal surface; readable markdown/code where available; sticky composer with IME insets, explicit send/interrupt controls. Toolbar is a proper top app bar, with tabs beneath.
- **Applied:** inset app bar, tonal role-based transcript cards, collapsed tool events and expandable technical details. **Outstanding:** true rich message blocks, fenced code copy, attachments, structured tool timelines and full streaming/queue parity.

### 07 — 09:23:14 — Mission tab loading
- **Observed:** almost entirely black with one anonymous spinner; no activity description or error/retry. The user cannot tell if the screen is empty, loading, disconnected or broken.
- **M3E direction:** morphing `LoadingIndicator` plus “Loading mission…” and a status/empty/failure state; replace raw response with mission timeline and task cards.
- **Applied:** labelled expressive loading state. **Outstanding:** mission/team-worker visualisation and live event lifecycle.

### 08 — 09:23:18 — Context tab
- **Observed:** full-screen monospace JSON shows field names such as `contextBudget`, `lastCallCompactionTokens` and nested token categories. No budget graphic, labels, human-readable amounts or warnings.
- **M3E direction:** top-level tonal context summary with progress and percentage; grouped breakdown rows with formatted labels; optional technical JSON disclosure.
- **Applied:** token progress summary, human labels, grouped field list, disclosure. **Outstanding:** rich category chart, token formatting, per-category semantic icons/colours.

### 09 — 09:23:26 — Workspace / Files
- **Observed:** title/status overlap, overlong session selection button, three tiny filter chips pretending to be tabs. Directory entries are irregular pill-shaped buttons of varying widths; no file type icon or folder hierarchy. Search contains refresh icon with unclear semantics; directory change occupies bottom with low priority.
- **M3E direction:** proper app bar, `PrimaryTabRow`, session switcher, grouped filename rows with path supporting text, familiar file affordances and meaningful empty view.
- **Applied:** app bar, tabs, selectable file rows and no-match state. **Outstanding:** tree navigation, file-type icons, syntax highlighting, file browsing breadcrumbs and search results separation.

### 10 — 09:23:52 — Workspace / Terminal
- **Observed:** user must manually supply a “Terminal ID” even after pressing “New shell”. Output never appears; the screen is mostly blank. Shell output area has no styled background and the composer looks like an arbitrary text field.
- **M3E direction:** create a UUID transparently with correct backend parameters, select running shells from a menu, display terminal status/output in a distinguishable monospace surface; fixed-size terminal controls and no ID-entry burden.
- **Applied:** generated terminal IDs, shell picker, backend output/status reducer, tonal terminal surface and composer. **Outstanding:** proper ANSI/VT emulator and resize/scrollback; need verified terminal lifecycle and disconnect tests.

### 11 — 09:23:56 — Workspace / Changes
- **Observed:** a Git failure (`not_git_repository`) prints as raw JSON. Commit, Push and Create PR remain visually actionable; the commit message is pinned to bottom under empty content, so actions appear to work despite missing Git repository.
- **M3E direction:** inline human-readable warning with recommended correction; gate Git actions on capability; show file diff/status UI only when supported.
- **Applied:** descriptive Git unavailable state and disabled actions when `success=false`. **Outstanding:** semantic hunk diff UI, branch workflow, stats and confirmation.

### 12 — 09:24:03 — Extensions index
- **Observed:** repeated raised cards with nearly identical weight, uniform chevrons and no grouping. Content forms a dense rectangle then empty screen; doesn't distinguish skill/tool/model groups.
- **M3E direction:** related expressive list rows grouped in a cohesive tonal surface, distinct leading category icons, supporting descriptions; top app bar owns title.
- **Applied:** grouped icon-led list and root header. **Outstanding:** section grouping by integration type and pinned recent integrations.

### 13 — 09:24:08 — MCP servers
- **Observed:** massive blank area between search field and “MCP server name / Add”; no spinner, no empty state, no guidance on transport/config/auth, no server status. Add is not an adequate setup form for most MCP servers.
- **M3E direction:** explicit empty/loading/error state, clear CTA for server setup, guided form/dialog with validation and auth state, list of configured servers with status.
- **Applied:** explanatory empty state with refresh; native top bar; supporting section description. **Outstanding:** full MCP transport/configuration and authentication forms; backend API schema must be respected.

### 14 — 09:24:20 — Settings index
- **Observed:** ungrouped stack of almost identical dark raised cards, with arrows but no icons, subtitles or information hierarchy. No separation between account/device options and app preferences.
- **M3E direction:** grouped `ListItem` systems (connection/security; appearance/language/notifications/battery; Droid defaults/about), shared surface and dividers, category icons, supporting text.
- **Applied:** grouped list hierarchy and shared list item component.

### 15 — 09:24:23 — Connection settings
- **Observed:** back/title collision; unformatted long websocket endpoint dominates form; Reconnect and Disconnect are separated with no connection identity/status capsule. Action relationship and security state unclear.
- **M3E direction:** status and paired computer summary first, endpoint in secondary details; grouped connection controls; reconnect prominent, disconnect guarded and lower emphasis.
- **Applied:** proper detail app bar and theme; **outstanding:** connection settings layout, confirmation and machine identity.

### 16 — 09:24:27 — Security settings
- **Observed:** security claim, single “Forget credentials” action and placeholder statement about biometrics on large otherwise blank screen. No credential persistence status, verification, last-used identity or destructive confirmation.
- **M3E direction:** secure credential status card, explicit erase confirmation, biometric lock onboarding only if implemented, no placeholder described as functional.
- **Outstanding:** full native lock and confirmation flow; do not claim available.

### 17 — 09:24:31 — Notifications settings
- **Observed:** technical disabled status and button to OS settings, plus admitted placeholder about service/push. No channel categories, notification permission action or explanation of consequences.
- **M3E direction:** platform-notification capability group, permission state, channel controls and actionable OS settings; show honest unsupported background push state.
- **Outstanding:** Android 13+ runtime permission, channels, native foreground service, push infrastructure.

### 18 — 09:24:34 — Battery settings
- **Observed:** one explanatory line and button, disconnected from actual current optimisation state; huge empty canvas. This is an OS-delegated function, but the UI does not explain when it matters.
- **M3E direction:** explicit current policy/state and why users may opt out, single OS settings button, no false promise of background connectivity.
- **Outstanding:** inspect optimisation state and native background lifecycle.

### 19 — 09:24:38 — Appearance
- **Observed:** theme choices are three vertically separated filter chips; no sample preview; no coordinated layout. Dynamic toggle could update preferences without visibly changing theme, defeating feedback.
- **M3E direction:** single-choice segmented selector, grouped dynamic-colour setting, real palette change via observer; use colour roles from the central design system throughout.
- **Applied:** segmented theme and live preference observation. **Outstanding:** system typography/display preview and RTL/contrast visual tests.

### 20 — 09:24:41 — Language
- **Observed:** three tiny vertically disconnected chips where radio rows would be more natural. Changing the choice has no visible language effect because UI strings are hardcoded in English.
- **M3E direction:** grouped native radio rows with system/default language semantics; use Android's per-app language API and string resources for all screens, including accessibility labels.
- **Applied:** grouped radio selection. **Outstanding:** actual native localization (English/Romanian) and Android locale integration; current preference is not yet equivalent to translated UI.

## Material 3 Expressive systems baseline

1. **Typography:** one complete type ramp (display/headline/title/body/label); root and detail app bars own heading roles; supporting text takes `onSurfaceVariant` and typography roles, not ad-hoc hard-coded `sp`.
2. **Colour:** `MaterialTheme.colorScheme` via dynamic Material You when available, plus paired dark/light fallback roles; containers define content colour. Never rely on a raw `Column` to establish default text contrast.
3. **Shape:** global shape scale (small/medium/large/extraLarge), related list elements grouped on shared surface; use pills for pill actions only, not as all-purpose file rows. Shape and elevation communicate hierarchy.
4. **Motion:** use `MaterialTheme.motionScheme = MotionScheme.expressive()`; prefer built-in component springs and state transitions, rather than arbitrary durations/easing. Verify reduce-motion accessibility.
5. **Component grammar:** `MediumFlexibleTopAppBar`, `TopAppBar`, `NavigationSuiteScaffold`, `PrimaryTabRow`, `SingleChoiceSegmentedButtonRow`, expressive `ListItem`, `LoadingIndicator`, `ModalBottomSheet`, tonal status surfaces. Components are chosen for behavior and meaning, not novelty.
6. **Responsive layout:** respect status, gesture, cutout and IME insets; adaptive navigation bar/rail; cap readable content width on large windows; test rotation, split-screen, tablet widths and font scale.
7. **Access and state:** content descriptions for icon-only actions, touch target ~48dp, clearly paired error/on-error colours, distinguish empty/loading/error/ready, no destructive action disguised as Cancel, no raw unlocalized protocol text as default UI.

## Verification and open issues

The screenshots predate the fixes described above. **No updated device screenshots have yet been provided**. GitHub Actions compile and signature verification are necessary but insufficient for visual certification. Visual acceptance must include 21 corresponding re-captures, portrait/landscape and font-scale tests, dynamic dark/light colors, emulator accessibility audit, and live Factory relay/terminal data.

Major product completeness gaps remain independent of appearance: secure Factory relay E2EE, backend RPC schemas for some extension features, Markdown/attachments, rich terminal ANSI, mission workers, biometric lock, background connectivity and full localization. The original design audit remains at [compose-migration-audit.md](compose-migration-audit.md).

## Documentation consulted

- [Material 3 in Jetpack Compose](https://developer.android.com/develop/ui/compose/designsystems/material3)
- [Material 3 Expressive and current component API](https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary)
- [Material 3 1.5.0 release notes](https://developer.android.com/jetpack/androidx/releases/compose-material3)
- [Large flexible top app bar](https://developer.android.com/reference/kotlin/androidx/compose/material3/LargeFlexibleTopAppBar.composable)
- [LoadingIndicator](https://developer.android.com/reference/kotlin/androidx/compose/material3/LoadingIndicator.composable)
- [Adaptive navigation](https://developer.android.com/develop/adaptive-apps/guides/build-adaptive-navigation)
- [Material 3 system insets](https://developer.android.com/develop/ui/compose/system/material-insets)
- [Android edge-to-edge](https://developer.android.com/develop/ui/compose/system/setup-e2e)
