# Draft release notes

These notes are a draft for version 1.0.0. Complete the [release checklist](RELEASE.md) before publication.

Monthlane is an Android home-screen dashboard for a clock, weather, calendar, events and tasks. Choose the components for each widget, select a date in the calendar, open events and tasks in their source apps, and complete writable tasks from the widget.

Weather is supplied by Breezy Weather and tasks by Tasks.org or compatible local providers. Weather offers Standard and Compact layouts. Calendar data comes from the Android Calendar Provider. Provider permissions and configuration are required for the corresponding components.

Known limitations: update timing depends on the launcher, Android scheduling and source apps; interactions can be delayed. Tasks.org's local provider API is unstable. Reconfiguration of existing widgets depends on launcher support. Installing a release signed with a new key requires uninstalling a debug-key test build and removes its local settings and cache.
