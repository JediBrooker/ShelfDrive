# ShelfDrive

ShelfDrive is an independent fork of the Audiobookshelf mobile app, tailored for personal Android and Android Automotive OS testing.

It connects to user-provided Audiobookshelf servers. It does not include or provide media content.

## Relationship to Audiobookshelf

ShelfDrive is not affiliated with, endorsed by, or published by the Audiobookshelf project or its maintainers.

Upstream projects:

- Audiobookshelf mobile app: https://github.com/advplyr/audiobookshelf-app
- Audiobookshelf server/project: https://github.com/advplyr/audiobookshelf

## License

This fork remains licensed under the GNU General Public License version 3. See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).

If you distribute a build of ShelfDrive, provide the corresponding source code for the exact build you distribute.

## Android

Install dependencies:

```shell
npm install
```

Generate and sync the web app into Android:

```shell
npm run sync
```

Build the Android debug app:

```shell
cd android
./gradlew :app:assembleDebug
```
