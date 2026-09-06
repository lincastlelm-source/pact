# User guide source

`PACT-User-Guide.pdf` in the project root is generated from these files.

The guide is authored as three HTML fragments (`part1`–`part3`) sharing `style.css`, concatenated
into `guide.html` and printed by headless Chrome. The phone screens are CSS mockups using the real
palette from `presentation/theme/Theme.kt`, not screenshots — see the note on the contents page.

## Regenerating

```bash
head -n -3 part1.html > guide.html && cat part2.html part3.html >> guide.html
```

Then, from this directory:

```bash
python -m http.server 8731
```

```bash
chrome --headless=new --disable-gpu --no-pdf-header-footer --virtual-time-budget=15000 --print-to-pdf="../../PACT-User-Guide.pdf" http://localhost:8731/guide.html
```

Serving over HTTP rather than opening `file://` matters: Chrome's headless renderer will not apply
a stylesheet loaded from a local file path in every configuration.
