# Wiki source

These files are the source of truth for the [Workbay wiki](https://github.com/neryosx/workbay/wiki).

GitHub stores a wiki in its own repository with no pull requests and no review, so the pages are
kept here as well: a wiki change arrives as an ordinary PR, gets a diff and a review like any
other change, and is copied to the wiki once it is merged.

One file per wiki page. The file name is the page title with spaces replaced by hyphens, which is
how the wiki itself names them, so `Recipes-and-Upgrades.md` is the page **Recipes and Upgrades**.
`_Sidebar.md` and `_Footer.md` are the wiki's own navigation files.

Images live in `../media/`. The pages reference them by absolute `raw.githubusercontent.com` URL
rather than a relative path, because the wiki is served from a different repository and a relative
path would not resolve there.

The crafting grids are built from one image per ingredient in `../media/slots/`, so that every
slot can carry its own link and its own hover text.
