# Contributing Guidelines

Thank you for contributing to PrivateCallingApp! Please read the guidelines below to maintain high code quality and consistency across the codebase.

## 🎨 Code Style
- Follow the official **Kotlin style guide**.
- Maintain clean layouts with Jetpack Compose, keeping state hoistings and lifecycle-aware coroutines properly scoped.
- Ensure all public APIs are documented with standard KDocs explaining parameters and exceptions.

## 🧪 Testing Requirements
- Code coverage goal is **60%+** on critical business logic paths (signaling client/server retries, authentication logic, identity management).
- Write Robolectric tests for local unit-level validation.
- All new features require matching unit tests or integration tests where applicable.

## 📋 Commit Message Format
We follow a structured commit convention to auto-generate changelogs:
`<type>(<scope>): <subject>`

### Examples:
- `fix(network): resolve WebSockets timeout crash in SignalingServer`
- `feat(media): integrate AEC and NS hardware controls in peer-connections`
- `docs(readme): update battery saver mitigation checklists`
- `chore(deps): bump compose-bom catalog definitions`

## 🔀 Branching Strategy
- **`main`**: Always stable, release-ready branch.
- **`develop`**: Integration branch for upcoming changes.
- **`feature/*`**: Short-lived feature branches targeting `develop`.
