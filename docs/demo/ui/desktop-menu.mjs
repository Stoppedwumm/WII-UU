import { systems } from "./systems.mjs";

// MenuView.java: 5 columns, 3 rows, and a separate focus ring for the dock.
const columns = 5;
const rows = 3;
const pageSize = columns * rows;
const byId = (id) => document.getElementById(id);

export function desktopMenu({ library, play, standalone }) {
  const grid = byId("games");
  const dock = [...byId("dock").querySelectorAll("button")];
  const baseSystem = systems.find(({ id }) => id === "gb");
  let system = standalone ? baseSystem : null;
  let selected = 0;
  let homeSelected = 0;
  let dockSelected = -1;

  function items() {
    return system ? (system.id === "gb" ? library() : []) : systems;
  }

  function focusDock(index) {
    dockSelected = index;
    drawCursor();
  }

  function drawCursor() {
    for (const tile of grid.children)
      tile.classList.toggle(
        "selected",
        dockSelected < 0 && Number(tile.dataset.index) === selected,
      );
    dock.forEach((button, i) =>
      button.classList.toggle("selected", i === dockSelected),
    );
  }

  function open(index = selected) {
    if (dockSelected >= 0) {
      dock[dockSelected].click();
      return;
    }
    const entry = items()[index];
    if (!entry) return;
    selected = index;
    if (system) {
      drawCursor();
      play(entry);
      return;
    }
    homeSelected = selected;
    system = entry;
    selected = 0;
    render();
  }

  function back() {
    if (standalone || !system) return;
    system = null;
    selected = homeSelected;
    dockSelected = -1;
    render();
  }

  function tile(entry, index) {
    const console = system ?? entry;
    const button = document.createElement("button");
    button.className = "tile";
    button.dataset.index = index;
    button.setAttribute(
      "aria-label",
      `${system ? "Play" : "Open"} ${entry.name}`,
    );
    button.style.setProperty("--console-color", console.color);

    const art = document.createElement("span");
    art.className = system ? "tile-art game-art" : "tile-art";
    const maker = document.createElement("small");
    maker.textContent = system
      ? console.shortName
      : console.maker.toUpperCase();
    art.append(maker);
    if (!system) {
      const year = document.createElement("small");
      year.textContent = console.year;
      art.append(year);
    }
    const initials = document.createElement("b");
    initials.textContent = system
      ? entry.name
          .trim()
          .split(/\s+/)
          .slice(0, 3)
          .map((word) => word[0])
          .join("")
          .toUpperCase()
      : console.shortName;
    art.append(initials);

    const title = document.createElement("span");
    title.className = "tile-title";
    title.textContent = entry.name;
    if (!system) {
      const count = console.id === "gb" ? library().length : 0;
      const subtitle = document.createElement("small");
      subtitle.textContent = count
        ? `${count} ${count === 1 ? "game" : "games"}`
        : "No games yet";
      if (count) subtitle.className = "has-games";
      title.append(subtitle);
    }
    button.append(art, title);
    button.onfocus = () => {
      selected = index;
      focusDock(-1);
    };
    button.onclick = () => {
      dockSelected = -1;
      open(index);
    };
    return button;
  }

  function render() {
    const entries = items();
    selected = Math.min(selected, Math.max(0, entries.length - 1));
    const page = Math.floor(selected / pageSize);
    grid.replaceChildren(
      ...entries
        .slice(page * pageSize, (page + 1) * pageSize)
        .map((entry, index) => tile(entry, page * pageSize + index)),
    );
    byId("crumb").textContent = standalone
      ? "Choccy Milk"
      : (system?.name ?? "Home");
    byId("menu-hint").textContent =
      `A: ${system ? "Play" : "Open"}   B: Back   L/R: Page   +: GamePad   −: Refresh`;
    byId("empty-system").hidden = entries.length > 0;
    if (!entries.length && system) {
      byId("empty-title").textContent = `No ${system.shortName} games yet`;
      byId("empty-help").textContent =
        "This system needs a browser emulator core. Game Boy / Color is available in this build.";
    }
    const pages = byId("pages");
    pages.replaceChildren();
    const total = Math.ceil(entries.length / pageSize);
    for (let i = 0; total > 1 && i < total; i++) {
      const dot = document.createElement("button");
      dot.setAttribute("aria-label", `Page ${i + 1}`);
      if (i === page) dot.setAttribute("aria-current", "page");
      dot.onclick = () => {
        selected = i * pageSize;
        dockSelected = -1;
        render();
      };
      pages.append(dot);
    }
    drawCursor();
  }

  function changePage(delta) {
    const end = Math.ceil(items().length / pageSize) - 1;
    const target = Math.max(
      0,
      Math.min(end, Math.floor(selected / pageSize) + delta),
    );
    selected = Math.min(
      items().length - 1,
      target * pageSize + (selected % pageSize),
    );
    dockSelected = -1;
    render();
  }

  function move(dx, dy) {
    const count = items().length;
    if (dockSelected >= 0) {
      if (dy < 0 && count) focusDock(-1);
      else if (dx) focusDock((dockSelected + dx + dock.length) % dock.length);
      return;
    }
    if (!count) {
      focusDock(0);
      return;
    }
    const page = Math.floor(selected / pageSize);
    const row = Math.floor((selected % pageSize) / columns);
    const column = selected % columns;
    const last = count - 1;
    const lastOnPage = Math.min(last, (page + 1) * pageSize - 1);
    const previous = selected;
    if (dx > 0) {
      if (column < columns - 1 && selected < last) selected++;
      else if (last > lastOnPage)
        selected = Math.min(last, (page + 1) * pageSize + row * columns);
    } else if (dx < 0) {
      if (column > 0) selected--;
      else if (page > 0)
        selected = page * pageSize - pageSize + row * columns + columns - 1;
    } else if (dy > 0) {
      if (selected + columns <= lastOnPage) selected += columns;
      else if (Math.floor((lastOnPage % pageSize) / columns) > row)
        selected = lastOnPage;
      else focusDock(0);
    } else if (dy < 0 && row > 0) {
      selected -= columns;
    }
    if (Math.floor(previous / pageSize) !== Math.floor(selected / pageSize))
      render();
    else drawCursor();
  }

  function showLibrary() {
    if (!system) homeSelected = selected;
    system = baseSystem;
    selected = 0;
    dockSelected = -1;
    render();
  }

  byId("menu-back").onclick = back;
  dock.forEach((button, index) => {
    button.addEventListener("focus", () => focusDock(index));
  });
  return { render, open, back, move, changePage, showLibrary };
}

// Browser drawing of MenuView.drawDockIcon, in normalized coordinates.
export function drawDockIcons() {
  for (const name of ["gamepad", "settings", "refresh", "power"]) {
    const canvas = document.createElement("canvas");
    canvas.width = canvas.height = 96;
    const g = canvas.getContext("2d");
    g.translate(48, 48);
    g.scale(39, 39);
    g.lineWidth = 0.17;
    g.lineCap = g.lineJoin = "round";
    g.strokeStyle = g.fillStyle = "#6b7178";
    const line = (x, y, x2, y2) => {
      g.beginPath();
      g.moveTo(x, y);
      g.lineTo(x2, y2);
      g.stroke();
    };
    const arc = (x, y, r, start, end, fill = false) => {
      g.beginPath();
      g.arc(x, y, r, start, end);
      if (fill) g.fill();
      else g.stroke();
    };
    if (name === "gamepad") {
      g.beginPath();
      g.roundRect(-1.04, -0.6, 2.08, 1.2, 0.3);
      g.stroke();
      g.fillRect(-0.45, -0.3, 0.9, 0.6);
      arc(-0.73, 0, 0.13, 0, 2 * Math.PI, true);
      arc(0.73, 0, 0.13, 0, 2 * Math.PI, true);
      document.getElementById("pad-status").prepend(canvas.cloneNode());
      const status = document.querySelector("#pad-status canvas");
      status.getContext("2d").drawImage(canvas, 0, 0);
    } else if (name === "settings") {
      arc(0, 0, 0.7, 0, 2 * Math.PI);
      arc(0, 0, 0.23, 0, 2 * Math.PI, true);
      for (let i = 0; i < 8; i++) {
        const angle = (i * Math.PI) / 4;
        line(
          0.7 * Math.cos(angle),
          0.7 * Math.sin(angle),
          Math.cos(angle),
          Math.sin(angle),
        );
      }
    } else if (name === "refresh") {
      arc(0, 0, 0.8, 0, 1.83 * Math.PI);
      line(0.69, -0.4, 0.26, -0.64);
      line(0.69, -0.4, 0.34, -0.05);
    } else {
      arc(0, 0.08, 0.82, -Math.PI / 3, (4 * Math.PI) / 3);
      line(0, -1.03, 0, 0);
    }
    document
      .querySelector(`#${name}-button .dock-circle`)
      .replaceChildren(canvas);
  }
}
