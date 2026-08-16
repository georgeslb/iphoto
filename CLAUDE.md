# iphoto

Spring Boot backend (`pom.xml`, `src/main/java/...`) with a React frontend at
`src/main/resources/static/src/` (built with Vite; build output goes to `frontend/dist/`).

Before making changes to the frontend, read these two files in
`src/main/resources/static/src/`:

- `specification.md` — domain concepts (Photo/Space/Album/Library/Garbage/Duplicate), feature
  breakdown, backend API endpoints, and architecture notes. Note in particular: Albums hold
  *references* to Main Space photos, Spaces *own* photos — these are not interchangeable
  concepts, even where their UI looks similar.
- `TODO.md` — running list of in-progress and planned refactor work. Update it whenever you
  complete or discover a task, so nothing gets lost between sessions.

When you learn something new about how the app behaves (especially anything backend-driven that
isn't obvious from the frontend code alone, e.g. the `/api/photos/selected` capability-flag
contract behind Header button visibility), add it to `specification.md` rather than letting it
live only in conversation history.

Frontend has a test suite (Vitest + React Testing Library, `npm test` from
`src/main/resources/static/`). Run it after touching `hooks/useMenuSelection.js`,
`hooks/usePhotoData.js`, `components/PhotoGrid.jsx`, `hooks/useAppNavigationAndSelection.js`,
`hooks/useAlbumManagement.js`, `hooks/useSpaceManagement.js`, or `hooks/useAuth.js` — those have
regression tests tied to specific bugs found before. When you fix a real bug elsewhere, consider
adding a test for it too rather than only fixing it.

Gotcha when writing tests for hooks: if a hook's `useEffect`/`useCallback` depends on an array or
object argument, never construct that argument inline inside the `renderHook(() => ...)` callback
(e.g. `useSomeHook(x ?? ['default'])`) — a fresh literal is created on every render, the dependency
never stabilizes, and the hook's effect loops forever (100%+ CPU, test hangs). Hoist it to a
variable outside the render callback instead.
