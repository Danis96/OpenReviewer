(function () {
  const layoutChip = document.getElementById("layout-chip");
  const zoomChip = document.getElementById("zoom-chip");
  const themeChip = document.getElementById("theme-chip");
  const countChip = document.getElementById("count-chip");
  const summaryEl = document.getElementById("summary");
  const statusEl = document.getElementById("status");
  const nodesEl = document.getElementById("nodes");
  const tooltipEl = document.getElementById("tooltip");
  const cyContainer = document.getElementById("cy");

  const state = {
    nodes: [],
    edges: [],
    clusterNodes: [],
    aggregateEdgeIds: [],
    summary: "Graph renderer shell loaded.",
    layout: "cose",
    theme: "dark",
    cy: null,
    cyLayout: null,
    lastTap: { id: null, at: 0 },
    selectedNodeIds: [],
    highlightedNodeIds: new Set(),
    highlightedEdgeIds: new Set(),
    expandedClusters: new Set(),
    clusterChildren: new Map(),
    clusterByNodeId: new Map()
  };
  let cytoscapeLoadAttempted = false;

  const KIND_COLORS = {
    UI: "#4d89ff",
    STATE: "#35c3a5",
    REPO: "#f4b740",
    DI: "#ad7cff",
    DATA: "#ff7a66",
    DOMAIN: "#7cc4ff",
    OTHER: "#7b879a"
  };

  const EDGE_COLORS = {
    IMPORT: "#7b8aa4",
    USES_TYPE: "#56b0ff",
    DI_PROVIDES: "#c792ff",
    MANIFEST: "#ff8d72",
    VIOLATION: "#ff5f5f",
    OTHER: "#7b8aa4"
  };

  function emit(type, payload) {
    if (typeof window.__openReviewerSendToJvm !== "function") return;
    try {
      window.__openReviewerSendToJvm(JSON.stringify({ type, payload: payload || {} }));
    } catch (error) {
      console.warn("Failed to emit JVM bridge event", error);
    }
  }

  function normalizeKind(kind) {
    const upper = String(kind || "OTHER").toUpperCase();
    return KIND_COLORS[upper] ? upper : "OTHER";
  }

  function normalizeEdgeType(edgeType) {
    const raw = String(edgeType || "IMPORT").toUpperCase();
    return EDGE_COLORS[raw] ? raw : "OTHER";
  }

  function normalizeSignals(signals) {
    if (!Array.isArray(signals)) return [];
    return signals
      .map((item) => String(item || "").trim())
      .filter(Boolean)
      .slice(0, 6);
  }

  function compactText(value, maxLen) {
    const text = String(value || "");
    if (text.length <= maxLen) return text;
    const head = text.slice(0, Math.max(8, Math.floor(maxLen * 0.58)));
    const tail = text.slice(-Math.max(5, Math.floor(maxLen * 0.24)));
    return `${head}…${tail}`;
  }

  function parseNode(node, index) {
    const id = String(node && node.id ? node.id : `node-${index}`);
    const rawLabel = String((node && node.label) || id);
    const fullLabel = rawLabel.includes("/") ? rawLabel.split("/").pop() : rawLabel;
    const displayBase = compactText(fullLabel, 28);
    const kind = normalizeKind(node && node.kind);
    const signals = normalizeSignals(node && node.signals);
    const group = String((node && node.group) || "root");
    const hotspotScore = Number((node && node.hotspotScore) || 0);
    const label = hotspotScore >= 6 ? `${displayBase} *` : displayBase;
    return {
      data: {
        id,
        label,
        fullLabel,
        path: String((node && node.path) || id),
        kind,
        kindColor: KIND_COLORS[kind],
        signals,
        signalCount: signals.length,
        hotspotScore,
        group
      },
      classes: [
        `kind-${kind.toLowerCase()}`,
        signals.length > 0 ? "has-signals" : "no-signals",
        hotspotScore >= 6 ? "hotspot" : ""
      ].join(" ")
    };
  }

  function parseEdge(edge, index) {
    const id = String((edge && edge.id) || `edge-${index}`);
    const source = String((edge && edge.source) || "");
    const target = String((edge && edge.target) || "");
    const edgeType = normalizeEdgeType(edge && edge.edgeType);
    return {
      data: {
        id,
        source,
        target,
        edgeType,
        edgeColor: EDGE_COLORS[edgeType]
      },
      classes: `edge-${edgeType.toLowerCase().replace(/[^a-z0-9-]/g, "-")}`
    };
  }

  function clusterId(group) {
    return `cluster:${String(group || "root")}`;
  }

  function updateMeta() {
    const cy = state.cy;
    const zoom = cy ? Math.round(cy.zoom() * 100) : 100;
    layoutChip.textContent = `layout: ${state.layout}`;
    zoomChip.textContent = `zoom: ${zoom}%`;
    themeChip.textContent = `theme: ${state.theme}`;
    countChip.textContent = `nodes: ${state.nodes.length} edges: ${state.edges.length}`;
  }

  function updateNodeList() {
    nodesEl.innerHTML = "";
    state.nodes.slice(0, 120).forEach((node) => {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "node";
      const hs = Number(node.data.hotspotScore || 0);
      const listLabel = node.data.fullLabel || node.data.label;
      btn.textContent = hs > 0 ? `${listLabel} [${hs}]` : listLabel;
      btn.dataset.nodeId = node.data.id;
      if (state.selectedNodeIds.includes(node.data.id)) {
        btn.classList.add("selected");
      }
      if (state.highlightedNodeIds.has(node.data.id)) {
        btn.style.borderColor = "#f4b740";
      }
      btn.addEventListener("click", function () {
        focusNode(node.data.id);
      });
      nodesEl.appendChild(btn);
    });
  }

  function setTheme(mode) {
    state.theme = mode === "light" ? "light" : "dark";
    document.documentElement.setAttribute("data-theme", state.theme === "light" ? "light" : "dark");
    updateMeta();
  }

  function ensureCy() {
    if (state.cy) return state.cy;
    if (typeof window.cytoscape !== "function") {
      maybeLoadLocalCytoscape();
      statusEl.textContent = "renderer: Cytoscape not found (fallback visual only)";
      return null;
    }
    const cy = window.cytoscape({
      container: cyContainer,
      elements: [],
      style: buildStyle(),
      minZoom: 0.1,
      maxZoom: 2.5,
      wheelSensitivity: 0.18,
      textureOnViewport: true,
      hideEdgesOnViewport: false,
      pixelRatio: 1
    });
    state.cy = cy;
    attachCyEvents(cy);
    statusEl.textContent = "renderer: Cytoscape ready";
    return cy;
  }

  function maybeLoadLocalCytoscape() {
    if (cytoscapeLoadAttempted) return;
    cytoscapeLoadAttempted = true;
    const script = document.createElement("script");
    script.src = "./vendor/cytoscape.min.js";
    script.async = true;
    script.onload = function () {
      statusEl.textContent = "renderer: Cytoscape loaded";
      const cy = ensureCy();
      if (!cy) return;
      if (state.nodes.length > 0 || state.edges.length > 0) {
        cy.startBatch();
        cy.elements().remove();
        cy.add(state.clusterNodes);
        cy.add(state.nodes);
        cy.add(state.edges);
        cy.endBatch();
        refreshClusterView(false);
        updateLabelVisibility();
        runLayout("SET_GRAPH");
      }
    };
    script.onerror = function () {
      statusEl.textContent = "renderer: Cytoscape missing (add ./vendor/cytoscape.min.js)";
    };
    document.head.appendChild(script);
  }

  function buildStyle() {
    return [
      {
        selector: "node",
        style: {
          width: "mapData(hotspotScore, 0, 24, 20, 40)",
          height: "mapData(hotspotScore, 0, 24, 20, 40)",
          label: "data(label)",
          "font-size": 11,
          "font-weight": 600,
          color: "#e6edf9",
          "text-wrap": "wrap",
          "text-max-width": 260,
          "text-valign": "bottom",
          "text-halign": "center",
          "text-margin-y": 14,
          "text-outline-color": "#0b1220",
          "text-outline-width": 2,
          "text-background-color": "#101826",
          "text-background-opacity": 0.72,
          "text-background-padding": 2,
          "text-border-radius": 4,
          "overlay-padding": 4,
          "overlay-opacity": 0,
          "background-color": "data(kindColor)",
          "border-color": "#0f1420",
          "border-width": 1.3
        }
      },
      {
        selector: "node.cluster",
        style: {
          shape: "round-rectangle",
          width: "label",
          height: 34,
          padding: "8px",
          "font-size": 11,
          "font-weight": 700,
          "background-color": "#1f2f4e",
          "border-color": "#5e85d8",
          "border-width": 1.8,
          color: "#eef3ff"
        }
      },
      {
        selector: "node.cluster.cluster-expanded",
        style: {
          "background-opacity": 0.05,
          "border-opacity": 0.25,
          color: "#9fb6e7",
          label: ""
        }
      },
      {
        selector: "node.has-signals",
        style: {
          "border-width": 2
        }
      },
      {
        selector: "node.hotspot",
        style: {
          "border-color": "#ffb047",
          "border-width": 3
        }
      },
      {
        selector: "node:selected",
        style: {
          "border-color": "#ffffff",
          "border-width": 3,
          "z-index": 20
        }
      },
      {
        selector: "node.highlight",
        style: {
          "border-color": "#f4b740",
          "border-width": 3.2
        }
      },
      {
        selector: "node.label-hidden",
        style: {
          label: ""
        }
      },
      {
        selector: ".hidden",
        style: {
          display: "none"
        }
      },
      {
        selector: "edge",
        style: {
          width: 1.6,
          opacity: 0.78,
          "curve-style": "bezier",
          "line-color": "data(edgeColor)",
          "target-arrow-color": "data(edgeColor)",
          "target-arrow-shape": "triangle",
          "arrow-scale": 0.65
        }
      },
      {
        selector: "edge.highlight",
        style: {
          width: 3,
          opacity: 1
        }
      },
      {
        selector: "edge.aggregate",
        style: {
          width: "mapData(count, 1, 12, 1.8, 5.5)",
          "line-style": "dashed",
          opacity: 0.88
        }
      },
      {
        selector: "edge.edge-violation",
        style: {
          width: 3.2,
          opacity: 0.96,
          "line-style": "dashed",
          "target-arrow-shape": "vee"
        }
      }
    ];
  }

  function buildClusters() {
    const childrenByCluster = new Map();
    const clusterByNodeId = new Map();
    state.nodes.forEach((node) => {
      const group = node.data.group || "root";
      const cid = clusterId(group);
      if (!childrenByCluster.has(cid)) childrenByCluster.set(cid, []);
      childrenByCluster.get(cid).push(node.data.id);
      clusterByNodeId.set(node.data.id, cid);
      node.data.parent = cid;
    });
    state.clusterChildren = childrenByCluster;
    state.clusterByNodeId = clusterByNodeId;
    state.clusterNodes = Array.from(childrenByCluster.entries()).map(([cid, childIds]) => {
      const groupName = cid.replace(/^cluster:/, "");
      const base = groupName.replace(/^feature:|^module:|^pkg:|^folder:|^config:/, "");
      const short = compactText((base.split("/").pop() || base).replace(/\./g, " "), 18);
      const label = `${short} (${childIds.length})`;
      return {
        data: {
          id: cid,
          label,
          kind: "OTHER",
          kindColor: "#1f2f4e",
          signalCount: 0,
          isCluster: true,
          group: groupName
        },
        classes: "cluster"
      };
    });
  }

  function applyDefaultClusterExpansion() {
    const clusterEntries = Array.from(state.clusterChildren.entries());
    state.expandedClusters = new Set();
    // Small filtered graphs should be immediately visible.
    if (state.nodes.length <= 80) {
      clusterEntries.forEach(([cid]) => state.expandedClusters.add(cid));
      return;
    }
    // In larger graphs, auto-expand singleton clusters so they don't disappear.
    clusterEntries.forEach(([cid, childIds]) => {
      if ((childIds || []).length <= 1) {
        state.expandedClusters.add(cid);
      }
    });
  }

  function isLeafVisible(nodeId) {
    const cid = state.clusterByNodeId.get(nodeId);
    if (!cid) return true;
    return state.expandedClusters.has(cid);
  }

  function representativeNodeId(nodeId) {
    if (isLeafVisible(nodeId)) return nodeId;
    const cid = state.clusterByNodeId.get(nodeId);
    return cid || nodeId;
  }

  function rebuildAggregateEdges() {
    const cy = state.cy;
    if (!cy) return;
    if (state.aggregateEdgeIds.length > 0) {
      state.aggregateEdgeIds.forEach((id) => {
        const ele = cy.getElementById(id);
        if (ele && !ele.empty()) cy.remove(ele);
      });
      state.aggregateEdgeIds = [];
    }
    const counters = new Map();
    state.edges.forEach((edge) => {
      const sourceVisible = isLeafVisible(edge.data.source);
      const targetVisible = isLeafVisible(edge.data.target);
      if (sourceVisible && targetVisible) return;
      const from = representativeNodeId(edge.data.source);
      const to = representativeNodeId(edge.data.target);
      if (!from || !to || from === to) return;
      const key = `${from}->${to}:${edge.data.edgeType}`;
      const prev = counters.get(key) || { from, to, edgeType: edge.data.edgeType, count: 0 };
      prev.count += 1;
      counters.set(key, prev);
    });
    const aggregateEdges = [];
    counters.forEach((item) => {
      const id = `agg:${item.from}->${item.to}:${item.edgeType}`;
      state.aggregateEdgeIds.push(id);
      aggregateEdges.push({
        data: {
          id,
          source: item.from,
          target: item.to,
          edgeType: item.edgeType,
          edgeColor: EDGE_COLORS[item.edgeType] || EDGE_COLORS.OTHER,
          count: item.count
        },
        classes: "aggregate"
      });
    });
    if (aggregateEdges.length > 0) {
      cy.add(aggregateEdges);
    }
  }

  function refreshClusterView(animateLayout) {
    const cy = state.cy;
    if (!cy) return;
    cy.batch(function () {
      cy.nodes(".cluster").forEach((clusterNode) => {
        const expanded = state.expandedClusters.has(clusterNode.id());
        clusterNode.toggleClass("cluster-expanded", expanded);
        clusterNode.removeClass("hidden");
      });
      state.nodes.forEach((node) => {
        const ele = cy.getElementById(node.data.id);
        if (!ele || ele.empty()) return;
        ele.toggleClass("hidden", !isLeafVisible(node.data.id));
      });
      state.edges.forEach((edge) => {
        const ele = cy.getElementById(edge.data.id);
        if (!ele || ele.empty()) return;
        const visible = isLeafVisible(edge.data.source) && isLeafVisible(edge.data.target);
        ele.toggleClass("hidden", !visible);
      });
      rebuildAggregateEdges();
    });
    updateNodeList();
    updateMeta();
    if (animateLayout) runLayout("CLUSTER_TOGGLE");
  }

  function expandCluster(clusterIdValue) {
    const id = String(clusterIdValue || "");
    if (!id) return;
    state.expandedClusters.add(id);
    refreshClusterView(false);
  }

  function collapseCluster(clusterIdValue) {
    const id = String(clusterIdValue || "");
    if (!id) return;
    state.expandedClusters.delete(id);
    refreshClusterView(false);
  }

  function collapseAllClusters() {
    state.expandedClusters.clear();
    refreshClusterView(false);
  }

  function attachCyEvents(cy) {
    cy.on("tap", "node", function (evt) {
      const node = evt.target;
      const nodeId = node.id();
      if (node.hasClass("cluster")) {
        if (state.expandedClusters.has(nodeId)) {
          collapseCluster(nodeId);
        } else {
          expandCluster(nodeId);
        }
        return;
      }
      const now = Date.now();
      const wasDouble = state.lastTap.id === nodeId && now - state.lastTap.at < 280;
      state.lastTap = { id: nodeId, at: now };

      state.selectedNodeIds = cy.$("node:selected").filter((n) => !n.hasClass("cluster")).map((n) => n.id());
      emit("NODE_CLICK", { nodeId });
      emit("SELECTION_CHANGED", { nodeIds: state.selectedNodeIds.slice(), nodeId });
      updateNodeList();
      if (wasDouble) {
        emit("NODE_DOUBLE_CLICK", { nodeId });
      }
    });

    cy.on("select unselect", "node", function () {
      state.selectedNodeIds = cy.$("node:selected").filter((n) => !n.hasClass("cluster")).map((n) => n.id());
      emit("SELECTION_CHANGED", { nodeIds: state.selectedNodeIds.slice(), nodeId: state.selectedNodeIds[0] || null });
      updateNodeList();
    });

    cy.on("zoom pan", throttle(function () {
      updateLabelVisibility();
      updateMeta();
      emit("VIEWPORT_CHANGED", {
        zoom: cy.zoom(),
        panX: cy.pan().x,
        panY: cy.pan().y
      });
    }, 100));

    cy.on("mouseover", "node", function (evt) {
      const n = evt.target;
      if (n.hasClass("cluster")) {
        tooltipEl.textContent = `${n.data("group") || n.id()}\nclick to expand/collapse`;
        const p = n.renderedPosition();
        tooltipEl.style.display = "block";
        tooltipEl.style.left = `${Math.round(p.x + 16)}px`;
        tooltipEl.style.top = `${Math.round(p.y + 12)}px`;
        return;
      }
      const signals = n.data("signals") || [];
      const signalText = signals.length === 0 ? "none" : signals.join(", ");
      tooltipEl.textContent = [
        n.data("fullLabel") || n.data("label") || n.id(),
        `path: ${n.data("path") || "n/a"}`,
        `kind: ${n.data("kind") || "OTHER"}`,
        `signals: ${signalText}`
      ].join("\n");
      const p = n.renderedPosition();
      tooltipEl.style.display = "block";
      tooltipEl.style.left = `${Math.round(p.x + 16)}px`;
      tooltipEl.style.top = `${Math.round(p.y + 12)}px`;
    });

    cy.on("mouseout", "node", function () {
      tooltipEl.style.display = "none";
    });
  }

  function updateLabelVisibility() {
    const cy = state.cy;
    if (!cy) return;
    const hide = cy.zoom() < 0.55;
    cy.batch(function () {
      cy.nodes().forEach((n) => {
        if (n.hasClass("cluster")) {
          n.removeClass("label-hidden");
        } else {
          n.toggleClass("label-hidden", hide);
        }
      });
    });
  }

  function stopCurrentLayout() {
    if (state.cyLayout && typeof state.cyLayout.stop === "function") {
      state.cyLayout.stop();
    }
    state.cyLayout = null;
  }

  function supportsLayout(cy, name) {
    try {
      const layout = cy.layout({ name });
      if (!layout) return false;
      if (typeof layout.stop === "function") layout.stop();
      return true;
    } catch (_error) {
      return false;
    }
  }

  function runLayout(trigger) {
    const cy = ensureCy();
    if (!cy) return;
    const size = cy.elements(":visible").size();
    let requested = state.layout || "cose";
    if (requested === "architecture") {
      stopCurrentLayout();
      state.cyLayout = cy.layout({
        name: "breadthfirst",
        fit: true,
        directed: true,
        animate: size <= 1200,
        padding: 30,
        spacingFactor: 1.2
      });
      state.cyLayout.run();
      return;
    }
    if (requested === "cose-bilkent" && !supportsLayout(cy, "cose-bilkent")) {
      requested = "cose";
      state.layout = "cose";
      layoutChip.textContent = "layout: cose";
    }
    stopCurrentLayout();
    const common = {
      name: requested,
      fit: true,
      animate: size <= 1200,
      padding: 26
    };
    const options =
      requested === "cose-bilkent"
        ? Object.assign({}, common, {
            randomize: false,
            nodeRepulsion: 5500,
            idealEdgeLength: 82
          })
        : Object.assign({}, common, {
            randomize: trigger !== "SET_GRAPH",
            nodeRepulsion: 4200,
            idealEdgeLength: 72
          });
    state.cyLayout = cy.layout(options);
    state.cyLayout.run();
  }

  function setGraph(payload) {
    if (!payload || typeof payload !== "object") return;
    const rawNodes = Array.isArray(payload.nodes) ? payload.nodes : [];
    const rawEdges = Array.isArray(payload.edges) ? payload.edges : [];
    state.nodes = rawNodes.map(parseNode);
    state.edges = rawEdges.map(parseEdge);
    state.aggregateEdgeIds = [];
    buildClusters();
    applyDefaultClusterExpansion();
    state.summary = typeof payload.summary === "string" ? payload.summary : state.summary;

    summaryEl.textContent = state.summary;
    updateMeta();
    updateNodeList();

    const cy = ensureCy();
    if (!cy) return;
    cy.startBatch();
    cy.elements().remove();
    cy.add(state.clusterNodes);
    cy.add(state.nodes);
    cy.add(state.edges);
    cy.endBatch();
    refreshClusterView(false);
    updateLabelVisibility();
    runLayout("SET_GRAPH");
  }

  function applyFilters(payload) {
    if (!payload || typeof payload !== "object") return;
    if (typeof payload.layout === "string" && payload.layout.trim()) {
      state.layout = payload.layout.trim();
    }
    if (payload.action === "FIT") {
      if (state.cy) state.cy.fit(state.cy.elements(), 34);
    } else if (payload.action === "RESET") {
      state.highlightedNodeIds = new Set();
      collapseAllClusters();
      const cy = ensureCy();
      if (cy) {
        cy.elements().unselect();
        cy.elements().removeClass("highlight");
        cy.fit(cy.elements(), 34);
      }
    } else if (payload.action === "RERUN_LAYOUT") {
      runLayout("RERUN_LAYOUT");
    } else if (payload.layout) {
      runLayout("APPLY_FILTERS");
    }
    updateMeta();
    updateNodeList();
  }

  function focusNode(nodeId) {
    const id = String(nodeId || "");
    if (!id) return;
    const cy = ensureCy();
    if (!cy) return;
    const parentCluster = state.clusterByNodeId.get(id);
    if (parentCluster && !state.expandedClusters.has(parentCluster)) {
      state.expandedClusters.add(parentCluster);
      refreshClusterView(false);
    }
    const node = cy.getElementById(id);
    if (!node || node.empty()) return;
    cy.elements().unselect();
    node.select();
    cy.animate({
      center: { eles: node },
      duration: 180
    });
    state.selectedNodeIds = [id];
    updateNodeList();
  }

  function setHighlight(payload) {
    const ids = Array.isArray(payload && payload.nodeIds) ? payload.nodeIds.map((v) => String(v)) : [];
    const edgeIds = Array.isArray(payload && payload.edgeIds) ? payload.edgeIds.map((v) => String(v)) : [];
    state.highlightedNodeIds = new Set(ids);
    state.highlightedEdgeIds = new Set(edgeIds);
    ids.forEach((id) => {
      const cid = state.clusterByNodeId.get(id);
      if (cid) state.expandedClusters.add(cid);
    });
    refreshClusterView(false);
    const cy = ensureCy();
    if (cy) {
      cy.elements().removeClass("highlight");
      ids.forEach((id) => {
        const n = cy.getElementById(id);
        if (n && !n.empty()) {
          n.addClass("highlight");
          if (edgeIds.length === 0) {
            n.connectedEdges().addClass("highlight");
          }
        }
      });
      edgeIds.forEach((id) => {
        const e = cy.getElementById(id);
        if (e && !e.empty()) e.addClass("highlight");
      });
    }
    updateNodeList();
  }

  function receiveCommand(raw) {
    let command;
    try {
      command = JSON.parse(raw);
    } catch (_error) {
      console.warn("Invalid command JSON", raw);
      return;
    }
    if (!command || typeof command.type !== "string") {
      console.warn("Missing command type", command);
      return;
    }
    const payload = command.payload || {};
    switch (command.type) {
      case "SET_GRAPH":
        setGraph(payload);
        break;
      case "APPLY_FILTERS":
        applyFilters(payload);
        break;
      case "FOCUS_NODE":
        focusNode(payload.nodeId);
        break;
      case "SET_HIGHLIGHT":
        setHighlight(payload);
        break;
      case "EXPAND_CLUSTER":
        if (payload && payload.clusterId) {
          expandCluster(String(payload.clusterId));
        }
        break;
      case "COLLAPSE_CLUSTER":
        if (payload && payload.clusterId === "__all__") {
          collapseAllClusters();
        } else if (payload && payload.clusterId) {
          collapseCluster(String(payload.clusterId));
        }
        break;
      case "SET_THEME":
        setTheme(payload.mode);
        break;
      default:
        console.warn("Unknown command type", command.type);
        break;
    }
  }

  function throttle(fn, waitMs) {
    let timer = null;
    let lastArgs = null;
    return function throttled() {
      lastArgs = arguments;
      if (timer) return;
      timer = setTimeout(function () {
        timer = null;
        fn.apply(null, lastArgs);
      }, waitMs);
    };
  }

  window.openReviewerBridge = {
    receiveCommand: receiveCommand,
    onJvmBridgeReady: function () {
      emit("READY", { version: 1, renderer: typeof window.cytoscape === "function" ? "cytoscape" : "fallback" });
    }
  };

  setTheme("dark");
  updateMeta();
  updateNodeList();
})();
