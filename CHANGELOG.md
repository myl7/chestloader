# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Add `/chestloader enable` and `/chestloader disable` to pause and resume loaders without dismantling them. Disabled state persists across restarts, and `/chestloader list` shows each loader's state with toggle controls.

### Changed

- Allow permission level 1 users to list, enable, and disable loaders. `/chestloader check` still requires permission level 2.

## [0.1.0] - 2026-08-14

[unreleased]: https://github.com/myl7/chestloader/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/myl7/chestloader/releases/tag/v0.1.0
