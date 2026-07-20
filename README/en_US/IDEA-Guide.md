# IDEA Guide

## Download & Installation

- [IntelliJ IDEA Official Download](https://www.jetbrains.com/idea/download/)
- The Community Edition is free and sufficient for Minecraft mod development
- [IntelliJ IDEA Documentation](https://www.jetbrains.com/idea/documentation/)

---

## Basic Usage

### Navigating to Source Files

- **`Ctrl + B`** / **`Ctrl + Click`** — Jump to the definition of a class/method/field
- **`Ctrl + Shift + I`** — Preview definition in a popup
- **`Alt + F7`** — Show all call sites of a method/class
- **`Ctrl + Alt + ←`** — Return to the previous location
- **`Ctrl + Alt + →`** — Go forward after navigating back
- **`Ctrl + N`** — Quickly open by class name
- **`Ctrl + Shift + N`** — Quickly open by file name
- **`Ctrl + Alt + Shift + N`** — Search method/variable names
- **`Ctrl + F`** — Search within the current file
- **`Ctrl + Shift + F`** — Search across the entire project |

### Using Git

IDEA has built-in Git support — no command line needed for common operations.

**Clone a repository:**
1. Menu `File` → `New` → `Project from Version Control...`
2. Enter the repository URL, choose a local path, and click `Clone`

**Common operations:**

- **`Ctrl + K`** — Commit: Open commit window, select files and write a commit message
- **`Ctrl + Shift + K`** — Push: Push local commits to the remote repository
- **`Ctrl + T`** — Pull: Pull the latest code from the remote repository
- **`Ctrl + D`** — Show Diff: View file changes in the file list
- **`Ctrl + Alt + Z`** — Revert: Discard changes to the current file

> The bottom status bar shows `Git: branch-name` on the left — click it to switch branches, create branches, or manage branches.

**Before committing:**
1. **Always pull the latest code before committing your files** (`Ctrl + T`) to avoid overwriting others' work
2. Push directly to the `main` branch
   1. Make sure to pull the latest code, commit your files, and resolve any code issues before pushing
3. If you're unsure whether your code is correct, create a `test` branch first (or push to it if it already exists)
4. The main author is Chinese — if you're non-Chinese and your code contains English comments, please use AI to translate them into Chinese before committing (except for contributor names and similar identifiers)

### Launching the Minecraft Client

1. Open the Gradle tool window on the right (`View` → `Tool Windows` → `Gradle`)
2. Expand `Tasks` → `forgegradle runs`
3. Double-click `runClient` to start the game client

> The first run will download Minecraft assets, which may take a while — please be patient.

---

## Links

- [IntelliJ IDEA Keymap Reference](https://resources.jetbrains.com/storage/products/intellij-idea/docs/IntelliJIDEA_ReferenceCard.pdf)
- [Kotlin Documentation](https://kotlinlang.org/docs/home.html)