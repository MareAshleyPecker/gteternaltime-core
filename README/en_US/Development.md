# en_US

If you, like me, enjoy GregTech and want to make a modpack, and happen to find my repository on GitHub —
if you're willing, you can join me in developing the core of this modpack.
A separate repository will be created later for modpack-related files.

---

## How to Use Git

```bash
# HTTPS
git clone https://github.com/username/repo.git

# SSH
git clone git@github.com:username/repo.git

# Clone to a specific directory
git clone https://github.com/username/repo.git target-folder
```

---

## If You Don't Have Git

### Windows

```bash
# winget (or search "git" in Microsoft Store)
winget install git

# Scoop
scoop install git

# Chocolatey
choco install git
```

> No package manager? Download the installer from [git-scm.com](https://git-scm.com/download/win)

### Linux

```bash
# Debian / Ubuntu
sudo apt install git

# CentOS / RHEL 7
sudo yum install git

# CentOS / RHEL 8+ / Fedora
sudo dnf install git

# Arch Linux
sudo pacman -S git

# openSUSE
sudo zypper install git

# Alpine
sudo apk add git

# Gentoo
sudo emerge --ask dev-vcs/git

# Solus
sudo eopkg install git

# Void Linux
sudo xbps-install -S git
```

---

Of course, there are still some basic requirements.

## Prerequisites

1. Basic knowledge of Kotlin syntax (ask AI if you don't know)
2. Familiarity with Minecraft type names (ask AI if you don't know)
3. Know how to add Gradle dependencies (Gradle scripts are split under the `scripts` folder)
4. Be able to organize your thoughts and write code
   - Languages: Kotlin / Java
   - Java is used for writing Mixins (just ask AI to write them if you can't) ([e.g.](../../src/main/java/rain/gtetcore/GTET/mixin/MixinFixGTBlocksNpe.java))
5. Add comments to key parts — if there are too many, ask AI to add a reference table above the class
6. <s>Watch your token usage when developing with AI</s>

---

## Links

- [GTM (GregTech Modern)](https://github.com/GregTechCEu/GregTech-Modern)
- [Kotlin Documentation](https://kotlinlang.org/docs/home.html)

## Tutorials (Work in Progress)

- [GTM Module Usage & Development](GTM-Dev-Tutorial-(WIP).md)
- [IDEA Guide](IDEA-Guide.md)