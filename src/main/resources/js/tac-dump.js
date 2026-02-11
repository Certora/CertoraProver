/**
 * TAC Dump Visualization Scripts
 *
 * This file contains all static JavaScript for the TAC dump HTML visualization.
 * Dynamic data (tooltip_cache, dataflowMap) must be injected before this script loads.
 */

// ============================================================================
// Interaction Scripts
// ============================================================================

function showCmd(cmdId) {
    document.getElementById(cmdId).style.display = "block"
}

function setDisplayClass(elem, className) {
    var wrapper = elem.closest(".cmds-list");
    wrapper.classList.remove("all", "uc-only", "close-to-uc");
    wrapper.classList.add(className);
}

function highlightActiveButton(elem, classNameToHighlight, wrapper) {
    classNameToHighlight = classNameToHighlight || 'uc-toggle-button';
    wrapper = wrapper || '.uc-toggle-buttons';
    var elems = elem.closest(wrapper).getElementsByClassName(classNameToHighlight);
    for (var i = 0; i < elems.length; ++i) {
        elems[i].style.backgroundColor = 'buttonface';
    }
    elem.style.backgroundColor = 'rgb(255, 237, 78)';
}

var showUcCmdsOnly = function () {
    highlightActiveButton(this)
    setDisplayClass(this, "uc-only")
}

var showCmdsCloseToUc = function () {
    highlightActiveButton(this)
    setDisplayClass(this, "close-to-uc")
}

var showAllCmds = function () {
    highlightActiveButton(this)
    setDisplayClass(this, "all");
}

// ============================================================================
// Constants and Helpers
// ============================================================================

var SVG_NS = "http://www.w3.org/2000/svg";
var COPY_FEEDBACK_DURATION = 1500;

function setAttrs(el, attrs) {
    Object.keys(attrs).forEach(function (k) { el.setAttribute(k, attrs[k]); });
}

function setStyles(el, styles) {
    Object.keys(styles).forEach(function (k) { el.style[k] = styles[k]; });
}

// ============================================================================
// State Variables
// ============================================================================

var currentSVG = "0";
var mainPanZoom = null;
var dataflowPanZoom = null;
var lastDataflowFocus = null;

var highlightedAnchor = null;
var highlightedDef = null;
var highlightedUseCls = null;
var highlightedIntFun = null;

var highlightedInputs = [];
var highlightedOutputs = [];
var dataflowGraph = {
    selectedVars: [],  // Variables explicitly clicked
    focusVar: null     // Currently focused variable
};

var layoutCache = {
    layout: null,
    selectedVarsKey: "",
    focusVar: null
};

// ============================================================================
// Helper Functions
// ============================================================================

// Get CSS custom property value
function getCssVar(name) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

// Get dataflow colors from CSS custom properties
function getDataflowColors() {
    return {
        input: getCssVar('--dataflow-input-color') || '#0066cc',
        output: getCssVar('--dataflow-output-color') || '#00cc66'
    };
}

// Get counter-example value for a variable (cexValues is injected by Kotlin)
function getCexValue(varName) {
    return (typeof cexValues !== 'undefined' && cexValues[varName]) || null;
}

// Render the CEX panel from cexValues map
function renderCexPanel() {
    var container = document.getElementById("cex-values");
    if (!container || typeof cexValues === 'undefined') return;

    var html = "";
    Object.keys(cexValues).forEach(function (varName) {
        var value = cexValues[varName];
        var escapedName = escapeHtml(varName);
        var escapedValue = escapeHtml(value);
        html += '<span class="use_' + escapedName + '">' + escapedName + '</span>=' + escapedValue + '<br/>\n';
    });
    container.innerHTML = html;
}

// Escape HTML special characters to prevent XSS
function escapeHtml(str) {
    if (!str) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

// Escape for use in JavaScript string within HTML attribute (e.g., onclick)
function escapeJsString(str) {
    if (!str) return '';
    return String(str)
        .replace(/\\/g, '\\\\')
        .replace(/"/g, '\\"')
        .replace(/'/g, "\\'");
}

// Get all blocks containers
function getAllContainers() {
    return document.querySelectorAll('[id^="blocksAndEdges"]');
}

// Find element within the currently visible blocks container
function findInCurrentContainer(elementId) {
    var container = document.getElementById("blocksAndEdges" + currentSVG);
    return container ? container.querySelector("#" + CSS.escape(elementId)) : document.getElementById(elementId);
}

// Find all elements within the currently visible blocks container matching a selector
function findAllInCurrentContainer(selector) {
    var container = document.getElementById("blocksAndEdges" + currentSVG);
    return container ? container.querySelectorAll(selector) : document.querySelectorAll(selector);
}

// Find SVG node within the currently visible SVG
function findNodeInCurrentSVG(nodeId) {
    var svgContainer = document.getElementById("svgOf" + currentSVG);
    return svgContainer ? svgContainer.querySelector("#" + CSS.escape(nodeId)) : document.getElementById(nodeId);
}

// Find SVG node in any SVG (used for clearing highlights across graph switches)
// Note: We loop through containers because the same node ID can exist in multiple
// sub-graphs (one per method call). Each sub-graph has its own SVG container,
// so we query within each container's scope rather than globally.
function findNodeInAnySVG(nodeId) {
    var svgContainers = document.querySelectorAll('[id^="svgOf"]');
    for (var i = 0; i < svgContainers.length; i++) {
        var node = svgContainers[i].querySelector("#" + CSS.escape(nodeId));
        if (node) return node;
    }
    return null;
}

// Find which graph contains a variable's definition
function findGraphWithDef(varName) {
    var containers = getAllContainers();
    for (var i = 0; i < containers.length; i++) {
        if (containers[i].querySelector('.def_' + CSS.escape(varName))) {
            return containers[i].id.replace("blocksAndEdges", "");
        }
    }
    return null;
}

// Toggle visibility between two elements
function toggleVisibility(currentId, newId, prefix, useDisplay) {
    var current = document.getElementById(prefix + currentId);
    var next = document.getElementById(prefix + newId);
    if (useDisplay) {
        if (current) current.style.display = "none";
        if (next) next.style.display = "block";
    } else {
        if (current) current.style.visibility = "hidden";
        if (next) next.style.visibility = "visible";
    }
}

// Set background color on element if it exists
function setBackground(el, color) {
    if (el) el.style.backgroundColor = color;
}


// Apply operation across all containers for a variable
function forEachContainerWithVar(varName, callback) {
    var containers = getAllContainers();
    for (var c = 0; c < containers.length; c++) {
        var els = containers[c].querySelectorAll('.def_' + CSS.escape(varName));
        for (var i = 0; i < els.length; i++) {
            callback(els[i], containers[c]);
        }
    }
}

// ============================================================================
// Browser History Navigation Support
// ============================================================================

function pushNavigationState() {
    var params = [];
    if (currentSVG !== "0") params.push('svg=' + encodeURIComponent(currentSVG));

    // Only include highlights if they are in the current graph to avoid "ghost" state
    var currentContainer = document.getElementById("blocksAndEdges" + currentSVG);
    var targetVar = null;
    var targetBlock = null;

    if (currentContainer) {
        if (highlightedDef && currentContainer.querySelector('.def_' + CSS.escape(highlightedDef))) {
            targetVar = highlightedDef;
            params.push('var=' + encodeURIComponent(targetVar));
        }
        if (highlightedAnchor && currentContainer.querySelector("#block" + CSS.escape(highlightedAnchor))) {
            targetBlock = highlightedAnchor;
            params.push('block=' + encodeURIComponent(targetBlock));
        }
    }

    if (dataflowGraph.selectedVars.length > 0) {
        params.push('dfVars=' + encodeURIComponent(dataflowGraph.selectedVars.join(',')));
    }

    var hash = params.length > 0 ? '#' + params.join('&') : "";

    // Normalize comparison: window.location.hash includes the '#'
    var currentHash = window.location.hash || "";
    if (currentHash === hash) return;

    var state = {
        svg: currentSVG,
        var: targetVar,
        block: targetBlock,
        dfVars: dataflowGraph.selectedVars.slice()
    };

    history.pushState(state, '', hash || window.location.pathname);
}

function restoreNavigationState(state) {
    var svg = "0", varName = null, block = null, dfVars = [];

    if (state) {
        svg = state.svg || "0";
        varName = state.var;
        block = state.block;
        dfVars = state.dfVars || [];
    } else {
        var hash = window.location.hash.substring(1);
        if (hash) {
            hash.split('&').forEach(function (pair) {
                var parts = pair.split('=');
                if (parts.length !== 2) return;
                var k = parts[0], v = decodeURIComponent(parts[1]);
                if (k === 'svg') svg = v;
                else if (k === 'var') varName = v;
                else if (k === 'block') block = v;
                else if (k === 'dfVars') dfVars = v.split(',');
            });
        }
    }

    // Apply states in sequence
    if (svg !== currentSVG) switchToGraph(svg, false);

    // Sync dataflow list
    dataflowGraph.selectedVars = dfVars;

    // Restore highlights precisely
    if (varName) {
        highlightDef(varName, false);
    } else {
        clearDefHighlight();
    }

    if (block) {
        highlightAnchor(block, false);
    } else {
        clearAnchorHighlight();
    }

    // Secondary UI sync for dataflow (if no var highlight triggered it)
    if (!varName) {
        if (dfVars.length > 0) renderDataflowGraph();
        else clearDataflowGraph(false);
    }
}

window.addEventListener('popstate', function (event) {
    restoreNavigationState(event.state);
});

window.addEventListener('DOMContentLoaded', function () {
    if (window.location.hash) {
        restoreNavigationState(null);
    }
});

// Core function to switch between graphs (visibility and pan-zoom)
function switchToGraph(newSvgId, pushHistory) {
    if (newSvgId === currentSVG && mainPanZoom) return;

    var oldSuffix = currentSVG === "0" ? "" : currentSVG;
    var newSuffix = newSvgId === "0" ? "" : newSvgId;

    // Destroy old pan-zoom
    var pannedCurrent = document.getElementById("theSVG" + oldSuffix);
    if (pannedCurrent) {
        try { svgPanZoom(pannedCurrent).destroy(); } catch (e) { }
    }

    // Toggle visibility
    toggleVisibility(currentSVG, newSvgId, "svgOf", false);
    toggleVisibility(currentSVG, newSvgId, "blocksAndEdges", false);
    toggleVisibility(currentSVG, newSvgId, "mag_", false);
    toggleVisibility(currentSVG, newSvgId, "successorMap", true);

    currentSVG = newSvgId;

    if (pushHistory) {
        // Clear highlights when manually navigating to a new graph context
        // This ensures the back button provides a clean "undo" state
        clearDefHighlight();
        clearAnchorHighlight();
        pushNavigationState();
    }
    // Initialize new pan-zoom
    var pannedNew = document.getElementById("theSVG" + newSuffix);
    if (pannedNew) {
        mainPanZoom = svgPanZoom(pannedNew, {
            zoomEnabled: true,
            controlIconsEnabled: true,
            fit: true,
            center: true
        });
    }

    if (pushHistory) pushNavigationState();
}

// Legacy alias for Kotlin HTML clicks
function toggleSVG(svgId) {
    switchToGraph(svgId, true);
}

function toggleSize() {
    var divCex = document.getElementById("cex");
    if (divCex) {
        divCex.style.width = divCex.style.width === "250px" ? "900px" : "250px";
    }
}

// ============================================================================
// Highlight Clear Functions
// ============================================================================

// Clear all highlights (blocks, edges, SVG nodes)
function clearAnchorHighlight() {
    if (!highlightedAnchor) return;

    var selector = "#block" + CSS.escape(highlightedAnchor) +
        ", #edgeS" + CSS.escape(highlightedAnchor) +
        ", #edgeT" + CSS.escape(highlightedAnchor) +
        ", #edgeP" + CSS.escape(highlightedAnchor);

    getAllContainers().forEach(function (c) {
        c.querySelectorAll(selector).forEach(function (el) { setBackground(el, "white"); });
    });

    clearAllSvgHighlights();
    highlightedAnchor = null;
}

function clearAllSvgHighlights() {
    // Find all SVG nodes with yellow fill (our highlight color) and reset them
    var svgContainers = document.querySelectorAll('[id^="svgOf"]');
    svgContainers.forEach(function (container) {
        // Look for polygon/ellipse elements with yellow fill
        var highlighted = container.querySelectorAll('[fill="yellow"]');
        highlighted.forEach(function (el) {
            // Restore original appearance from data attributes, or use defaults
            el.setAttribute("fill", el.getAttribute("data-orig-fill") || "white");
            el.setAttribute("stroke-width", el.getAttribute("data-orig-stroke-width") || "1px");
            el.setAttribute("stroke-dasharray", el.getAttribute("data-orig-stroke-dasharray") || "none");
        });
    });
}

function clearDefHighlight() {
    if (!highlightedDef) return;

    var selector = '.def_' + CSS.escape(highlightedDef) + ', .' + CSS.escape(highlightedUseCls);
    getAllContainers().forEach(function (c) {
        c.querySelectorAll(selector).forEach(function (el) { el.style.removeProperty('background-color'); });
    });

    // Also clear in the CEX (counterexample) panel
    var cexPanel = document.getElementById("cex");
    if (cexPanel) {
        cexPanel.querySelectorAll('.' + CSS.escape(highlightedUseCls)).forEach(function (el) {
            el.style.removeProperty('background-color');
        });
    }

    highlightedDef = null;
    highlightedUseCls = null;
}


function clearIntFunHighlight() {
    if (!highlightedIntFun) return;
    setBackground(document.getElementById("intfunStart_" + highlightedIntFun), "white");
    setBackground(document.getElementById("intfunEnd_" + highlightedIntFun), "white");
    highlightedIntFun = null;
}

function clearDataflowHighlights() {
    // Clear outline styles for inputs and outputs
    var allVars = highlightedInputs.concat(highlightedOutputs);
    allVars.forEach(function (varName) {
        forEachContainerWithVar(varName, function (el) {
            el.style.removeProperty('outline');
            el.style.removeProperty('outline-offset');
        });
    });
    highlightedInputs = [];
    highlightedOutputs = [];
}

// ============================================================================
// Highlight Functions
// ============================================================================

function highlightAnchor(anchorId, pushHistory) {
    if (pushHistory === undefined) pushHistory = true;
    var messagesDiv = document.getElementById("messages");
    messagesDiv.innerHTML = "";
    clearAnchorHighlight();
    highlightedAnchor = anchorId;

    var newBlock = findInCurrentContainer("block" + anchorId);
    var newEdgesS = findAllInCurrentContainer(".edgeS" + CSS.escape(anchorId));
    var newEdgesT = findAllInCurrentContainer(".edgeT" + CSS.escape(anchorId));
    var newEdgesP = findAllInCurrentContainer(".edgeP" + CSS.escape(anchorId));
    var newNode = findNodeInCurrentSVG(anchorId);

    if (newBlock) {
        // Find the block-wrapper parent and scroll container
        var blockWrapper = newBlock.closest('.block-wrapper');
        var scrollContainer = document.getElementById("blocksHtml" + currentSVG);
        if (blockWrapper && scrollContainer) {
            // Calculate offset and scroll manually (works with sticky headers)
            var wrapperTop = blockWrapper.offsetTop;
            scrollContainer.scrollTo({ top: wrapperTop, behavior: 'smooth' });
        } else {
            // Fallback
            newBlock.scrollIntoView({ block: 'start', behavior: 'smooth' });
        }
        setBackground(newBlock, "yellow");
        if (newEdgesS.length) newEdgesS.forEach(function (el) { setBackground(el, "yellow"); });
        if (newEdgesT.length) newEdgesT.forEach(function (el) { setBackground(el, "yellow"); });
        if (newEdgesP.length) newEdgesP.forEach(function (el) { setBackground(el, "yellow"); });
    } else {
        messagesDiv.innerHTML += "block " + anchorId + " does not exist.<br/>";
    }

    if (newNode) {
        var nodeShape = newNode.children[1];
        // Store original values as data attributes for restoration
        nodeShape.setAttribute("data-orig-fill", nodeShape.getAttribute("fill") || "white");
        nodeShape.setAttribute("data-orig-stroke-width", nodeShape.getAttribute("stroke-width") || "1px");
        nodeShape.setAttribute("data-orig-stroke-dasharray", nodeShape.getAttribute("stroke-dasharray") || "none");
        nodeShape.setAttribute("fill", "yellow");
        nodeShape.setAttribute("stroke-width", "3px");
        nodeShape.setAttribute("stroke-dasharray", "10,4");
    }

    if (newEdgesS.length > 0) newEdgesS[0].scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    if (newEdgesP.length > 0) newEdgesP[0].scrollIntoView({ block: 'nearest', behavior: 'smooth' });

    if (pushHistory) pushNavigationState();
}

// Core definition highlighting (without dataflow navigation)
function doHighlightDef(def) {
    clearDefHighlight();
    highlightedDef = def;
    highlightedUseCls = "use_" + def;

    var selector = '.def_' + CSS.escape(def) + ', .' + CSS.escape(highlightedUseCls);
    getAllContainers().forEach(function (c) {
        c.querySelectorAll(selector).forEach(function (el) { el.style.backgroundColor = "yellow"; });
    });

    // Also highlight in the CEX (counterexample) panel
    var cexPanel = document.getElementById("cex");
    if (cexPanel) {
        cexPanel.querySelectorAll('.' + CSS.escape(highlightedUseCls)).forEach(function (el) {
            el.style.backgroundColor = "yellow";
        });
    }

    var currentDefs = document.querySelectorAll('#blocksAndEdges' + currentSVG + ' .def_' + CSS.escape(def));
    if (currentDefs.length > 0) {
        currentDefs[0].scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    }
}

function highlightDef(def, pushHistory) {
    if (pushHistory === undefined) pushHistory = true;
    var messagesDiv = document.getElementById("messages");
    messagesDiv.innerHTML = "";
    messagesDiv.style.backgroundColor = "";

    // Automatically jump to the correct subgraph if definition is found elsewhere
    var graphId = findGraphWithDef(def);
    if (graphId && graphId !== currentSVG) {
        switchToGraph(graphId, false);
    }

    doHighlightDef(def);

    // Check if def was found
    var defFound = graphId !== null;
    if (!defFound) {
        messagesDiv.innerHTML = "def " + def + " does not exist in any method.";
        messagesDiv.style.backgroundColor = "red";
    }

    // Handle dataflow graph
    var isAlreadySelected = dataflowGraph.selectedVars.indexOf(def) >= 0;
    var isHighlighted = highlightedInputs.indexOf(def) >= 0 || highlightedOutputs.indexOf(def) >= 0;
    var isConnected = isConnectedToGraph(def);

    if (isAlreadySelected || isHighlighted || isConnected) {
        addToDataflowGraph(def);
    } else {
        clearDataflowGraph(false);
        addToDataflowGraph(def);
    }

    if (pushHistory) pushNavigationState();
}

function toggleInternalFun(id) {
    clearIntFunHighlight();
    highlightedIntFun = id;
    setBackground(document.getElementById("intfunStart_" + id), "#ffccff");
    setBackground(document.getElementById("intfunEnd_" + id), "#ffccff");
}

// ============================================================================
// Dataflow Graph
// ============================================================================

// Check if variable is connected to any variable in the graph (via dataflowMap)
function isConnectedToGraph(varName) {
    if (dataflowGraph.selectedVars.length === 0) return false;

    var varInfo = dataflowMap[varName] || { inputs: [], outputs: [] };

    for (var i = 0; i < dataflowGraph.selectedVars.length; i++) {
        var selected = dataflowGraph.selectedVars[i];
        var selectedInfo = dataflowMap[selected] || { inputs: [], outputs: [] };

        // Check all four directions
        if (selectedInfo.inputs && selectedInfo.inputs.indexOf(varName) >= 0) return true;
        if (selectedInfo.outputs && selectedInfo.outputs.indexOf(varName) >= 0) return true;
        if (varInfo.inputs && varInfo.inputs.indexOf(selected) >= 0) return true;
        if (varInfo.outputs && varInfo.outputs.indexOf(selected) >= 0) return true;
    }
    return false;
}

// Add variable to graph and set as focus
function addToDataflowGraph(varName) {
    if (dataflowGraph.selectedVars.indexOf(varName) < 0) {
        dataflowGraph.selectedVars.push(varName);
    }
    dataflowGraph.focusVar = varName;

    showDataflowHighlights(varName);
    renderDataflowGraph();
}

// Clear the graph
function clearDataflowGraph(pushHistory) {
    if (pushHistory === undefined) pushHistory = true;
    dataflowGraph.selectedVars = [];
    dataflowGraph.focusVar = null;
    lastDataflowFocus = null; // Clear focus tracking to trigger fit on next var choice
    clearDataflowHighlights();
    renderDataflowGraph();
    if (pushHistory) pushNavigationState();
}

// Highlight inputs/outputs of focused variable in the code
function showDataflowHighlights(varName) {
    clearDataflowHighlights();
    var info = dataflowMap[varName];
    if (!info) return;

    var colors = getDataflowColors();
    function highlight(vars, color, arr) {
        (vars || []).forEach(function (v) {
            forEachContainerWithVar(v, function (el) {
                el.style.outline = "2px solid " + color;
                el.style.outlineOffset = "1px";
                arr.push(v);
            });
        });
    }
    highlight(info.inputs, colors.input, highlightedInputs);
    highlight(info.outputs, colors.output, highlightedOutputs);
}

// Compute graph layout - BFS from focus using dataflowMap (with caching)
function computeGraphLayout(forceRecompute) {
    if (!dataflowGraph.focusVar) return { layers: {}, nodeLayer: {}, edges: [] };

    // Check cache validity
    var selectedVarsKey = dataflowGraph.selectedVars.slice().sort().join(",");
    if (!forceRecompute &&
        layoutCache.layout &&
        layoutCache.selectedVarsKey === selectedVarsKey &&
        layoutCache.focusVar === dataflowGraph.focusVar) {
        return layoutCache.layout;
    }

    var layout = doComputeGraphLayout();

    // Update cache
    layoutCache.layout = layout;
    layoutCache.selectedVarsKey = selectedVarsKey;
    layoutCache.focusVar = dataflowGraph.focusVar;

    return layout;
}

// Internal: actual layout computation using Dagre
function doComputeGraphLayout() {
    var focus = dataflowGraph.focusVar;
    var focusInfo = dataflowMap[focus] || { inputs: [], outputs: [] };

    // Collect and sort nodes
    var visibleNodes = new Set(dataflowGraph.selectedVars);
    [focusInfo.inputs, focusInfo.outputs].forEach(function (arr) {
        (arr || []).forEach(function (v) { visibleNodes.add(v); });
    });
    var sortedNodes = Array.from(visibleNodes).sort();

    var g = new graphlib.Graph();
    g.setGraph({ rankdir: 'LR', nodesep: 40, ranksep: 80, marginx: 40, marginy: 40 });
    g.setDefaultEdgeLabel(function () { return {}; });

    var nodeH = 24;
    sortedNodes.forEach(function (v) {
        g.setNode(v, { label: v, width: Math.max(80, v.length * 7 + 16), height: nodeH });
    });

    // Add edges (deterministically)
    var edgeList = [];
    sortedNodes.forEach(function (v) {
        (dataflowMap[v]?.outputs || []).forEach(function (out) {
            if (visibleNodes.has(out)) edgeList.push({ v: v, w: out });
        });
    });

    edgeList.sort(function (a, b) { return a.v.localeCompare(b.v) || a.w.localeCompare(b.w); })
        .forEach(function (e) { g.setEdge(e.v, e.w); });

    dagre.layout(g);

    var nodePositions = {};
    g.nodes().forEach(function (v) {
        var node = g.node(v);
        if (node) nodePositions[v] = { x: node.x, y: node.y, width: node.width, height: node.height };
    });

    var graphInfo = g.graph();
    return {
        nodePositions: nodePositions,
        edges: g.edges().map(function (e) {
            return { from: e.v, to: e.w, points: g.edge(e).points || [] };
        }),
        graphWidth: graphInfo.width || 200,
        graphHeight: graphInfo.height || 100
    };
}


// Redraw dataflow edges (uses cached layout)
function redrawDataflowEdges(forceFit) {
    if (!dataflowGraph.focusVar) return;
    renderDataflowGraph(forceFit);
}

// Render the graph
function renderDataflowGraph(forceFit) {
    var svg = document.getElementById("dataflow-svg");
    var viewport = document.getElementById("dataflow-viewport");
    var hint = document.getElementById("dataflow-hint");
    var clearBtn = document.getElementById("dataflow-clear-btn");
    if (!svg || !viewport) return;

    if (!dataflowGraph.focusVar) {
        viewport.innerHTML = "";
        if (hint) hint.style.display = "block";
        if (clearBtn) clearBtn.style.display = "none";
        return;
    }

    if (hint) hint.style.display = "none";
    if (clearBtn) clearBtn.style.display = "inline-block";

    var layout = computeGraphLayout(), selectedSet = new Set(dataflowGraph.selectedVars);

    // Track existing elements in viewport
    var existing = {};
    Array.from(viewport.children).forEach(function (el) {
        var id = el.getAttribute("data-id") || el.getAttribute("data-edge-id");
        if (id) existing[id] = el;
    });

    // Render Nodes (foreignObject)
    Object.keys(layout.nodePositions).forEach(function (v) {
        var pos = layout.nodePositions[v], el = existing[v];
        if (!el) {
            el = document.createElementNS(SVG_NS, "g");
            el.setAttribute("data-id", v);
            var fo = document.createElementNS(SVG_NS, "foreignObject");
            fo.appendChild(createNodeElement(v, selectedSet));
            el.appendChild(fo);
            viewport.appendChild(el);
        } else {
            var isF = v === dataflowGraph.focusVar, isS = selectedSet.has(v);
            var div = el.querySelector(".dataflow-node");
            if (div) div.className = "dataflow-node" + (isF ? " focus" : isS ? " selected" : " output");
        }
        delete existing[v];

        var fo = el.querySelector("foreignObject");
        setAttrs(fo, { x: pos.x - pos.width / 2, y: pos.y - pos.height / 2, width: pos.width, height: pos.height });
    });

    // Render Edges
    var colors = getDataflowColors(), focus = dataflowGraph.focusVar, fI = dataflowMap[focus] || { inputs: [], outputs: [] };
    layout.edges.forEach(function (edge) {
        var key = "e:" + edge.from + "→" + edge.to, el = existing[key];
        if (!el) {
            el = document.createElementNS(SVG_NS, "path");
            el.setAttribute("data-edge-id", key);
            viewport.appendChild(el);
        }
        delete existing[key];

        if (edge.points?.length >= 2) {
            var d = "M " + edge.points[0].x + " " + edge.points[0].y;
            for (var i = 1; i < edge.points.length; i++) d += " L " + edge.points[i].x + " " + edge.points[i].y;
            var isIn = edge.to === focus && fI.inputs?.indexOf(edge.from) >= 0;
            var isOut = edge.from === focus && fI.outputs?.indexOf(edge.to) >= 0;
            setAttrs(el, { d: d, stroke: isIn ? colors.input : isOut ? colors.output : "#888", "marker-end": "url(#dataflow-arrow-" + (isIn ? "input" : isOut ? "output" : "neutral") + ")", fill: "none", "stroke-width": "1.5" });
        }
    });

    // Remove old elements
    Object.keys(existing).forEach(function (k) { viewport.removeChild(existing[k]); });

    // Initialize or update pan-zoom
    if (!dataflowPanZoom) {
        dataflowPanZoom = svgPanZoom('#dataflow-svg', { viewportSelector: '#dataflow-viewport', zoomEnabled: true, controlIconsEnabled: true, fit: true, center: true });
        lastDataflowFocus = null; // Force first fit check below
    }

    dataflowPanZoom.resize();
    dataflowPanZoom.updateBBox();

    // Only auto-fit if explicitly requested (resize) or if no focus was set (initial/reset)
    if (forceFit || !lastDataflowFocus) {
        dataflowPanZoom.fit().center();
        // Avoid extreme zoom for small graphs/large containers
        if (dataflowPanZoom.getZoom() > 0.8) {
            dataflowPanZoom.zoom(0.8).center();
        }
    }
    lastDataflowFocus = dataflowGraph.focusVar;
}

// Create a node element with common properties
function createNodeElement(v, selectedSet) {
    var el = document.createElement("div"), isF = v === dataflowGraph.focusVar, isS = selectedSet.has(v);
    el.className = "dataflow-node" + (isF ? " focus" : isS ? " selected" : " output");
    el.textContent = v;
    el.setAttribute("data-var", v);
    el.onclick = function () { onGraphNodeClick(v); };

    var val = getCexValue(v);
    if (val) {
        var tt = document.getElementById('dataflow-tooltip');
        el.onmouseenter = function () { if (tt) { tt.textContent = v + ' = ' + val; tt.style.display = 'block'; } };
        el.onmouseleave = function () { if (tt) tt.style.display = 'none'; };
        el.onmousemove = function (e) { if (tt) setStyles(tt, { left: (e.clientX + 10) + 'px', top: (e.clientY + 10) + 'px' }); };
    }
    return el;
}

function onGraphNodeClick(varName) {
    var graphId = findGraphWithDef(varName);
    // Don't push history here, let highlightDef do it once at the end
    if (graphId && graphId !== currentSVG) switchToGraph(graphId, false);
    highlightDef(varName, true);
}

// ============================================================================
// Tooltip Functions
// ============================================================================

var activeTooltip = null;
var hoveredTooltipSpan = null; // Track which tooltip span the mouse is currently over
var COPY_FEEDBACK_DURATION = 1500;

function hideTooltip(tooltipSpan) {
    if (!tooltipSpan) return;
    var tt = tooltipSpan.querySelector('.tooltiptext');
    if (tt) {
        tt.innerHTML = "";
        tt.style.visibility = "hidden";
        tt.classList.remove('copied');
    }
    if (activeTooltip === tooltipSpan) {
        activeTooltip = null;
    }
}

function showTooltip(tooltipSpan) {
    // Hide any other visible tooltip first
    if (activeTooltip && activeTooltip !== tooltipSpan) {
        hideTooltip(activeTooltip);
    }
    var tt = tooltipSpan.querySelector('.tooltiptext');
    var tooltipId = tt && tt.getAttribute('tooltipatt');
    if (tooltipId && tooltip_cache[tooltipId]) {
        tt.innerHTML = '<span class="tooltip-content">' + tooltip_cache[tooltipId] + '</span>' +
            '<span class="copy-hint">(press c to copy)</span>';
        tt.style.visibility = "visible";
        tt.classList.remove('copied');
        activeTooltip = tooltipSpan;
    }
}

function copyActiveTooltip() {
    if (!hoveredTooltipSpan) return;
    var tt = hoveredTooltipSpan.querySelector('.tooltiptext');
    if (tt) {
        copyToClipboard(tt);
    }
}

function copyToClipboard(tooltiptext) {
    // Get only the tooltip content, excluding the copy hint
    var contentSpan = tooltiptext.querySelector('.tooltip-content');
    var text = contentSpan ? contentSpan.innerHTML : tooltiptext.innerHTML;
    if (text) {
        navigator.clipboard.writeText(text).then(function () {
            tooltiptext.classList.add('copied');
            setTimeout(function () {
                tooltiptext.classList.remove('copied');
            }, COPY_FEEDBACK_DURATION);
        });
    }
}

// ============================================================================
// Internal Helpers
// ============================================================================

// ============================================================================
// DOM Utilities
// ============================================================================

/**
 * Initialize TAC dump visualization.
 * Must be called after tooltip_cache and dataflowMap are defined.
 */
function initTacDump() {
    // Render CEX panel from cexValues map
    renderCexPanel();

    // Create dataflow tooltip element for mouse-following tooltips
    var dfTooltip = document.createElement('div');
    dfTooltip.id = 'dataflow-tooltip';
    dfTooltip.style.cssText = 'position:fixed;background:#333;color:#fff;padding:4px 8px;border-radius:4px;font-size:11px;pointer-events:none;z-index:10000;display:none;max-width:300px;word-break:break-all;';
    document.body.appendChild(dfTooltip);

    // Set up tooltip event listeners
    Array.from(document.getElementsByClassName('tooltip')).forEach(function (me) {
        var tooltiptext = me.querySelector('.tooltiptext');

        me.addEventListener("mouseenter", function () {
            hoveredTooltipSpan = this;
            showTooltip(this);
        });

        me.addEventListener("mouseleave", function () {
            hoveredTooltipSpan = null;
            hideTooltip(this);
        });

        if (tooltiptext) {
            tooltiptext.addEventListener("click", function (e) {
                e.stopPropagation();
                copyToClipboard(this);
            });
        }
    });

    // Keyboard shortcut to copy tooltip content while hovering
    // Press 'c' to copy the tooltip text of the currently hovered element
    document.addEventListener("keydown", function (e) {
        if (e.key === 'c' && !e.ctrlKey && !e.metaKey && !e.altKey) {
            // Only trigger if not typing in an input field
            var tagName = document.activeElement.tagName.toLowerCase();
            if (tagName !== 'input' && tagName !== 'textarea') {
                copyActiveTooltip();
            }
        }
    });

    // Initialize SVG pan-zoom for the main graph
    mainPanZoom = svgPanZoom('#theSVG', {
        zoomEnabled: true,
        controlIconsEnabled: true,
        fit: true,
        center: true
    });

    // Set up dataflow graph tooltip (SVG doesn't support fixed positioning easily, so keep it in document.body)
    var dfSvg = document.getElementById("dataflow-svg");
    if (dfSvg) {
        // svg-pan-zoom will be initialized on first render in renderDataflowGraph
    }

    // Set up bottom bar section resizing via dividers
    initBottomBarResizers();
}

// Initialize draggable dividers between bottom bar sections
// Uses delegated event handlers to avoid creating multiple document-level listeners
function initBottomBarResizers() {
    var bottomBar = document.getElementById("bottom-bar");
    if (!bottomBar) return;

    var vertResizer = document.getElementById("bottom-bar-resizer");
    var sections = bottomBar.querySelectorAll(':scope > div:not(.section-resizer)');

    // Insert resizer dividers between sections
    if (sections.length >= 2) {
        for (var i = 0; i < sections.length - 1; i++) {
            var resizer = document.createElement('div');
            resizer.className = 'section-resizer';
            resizer.setAttribute('data-index', i);
            sections[i].after(resizer);
        }
    }

    // Shared resize state
    var resizeState = {
        active: false,
        type: null,           // 'vertical' or 'horizontal'
        startX: 0,
        startY: 0,
        startHeight: 0,
        leftSection: null,
        rightSection: null,
        leftStartWidth: 0,
        rightStartWidth: 0
    };

    // Vertical resizer mousedown
    if (vertResizer) {
        vertResizer.addEventListener('mousedown', function (e) {
            resizeState.active = true;
            resizeState.type = 'vertical';
            resizeState.startY = e.clientY;
            resizeState.startHeight = bottomBar.offsetHeight;
            document.body.style.cursor = 'row-resize';
            e.preventDefault();
        });
    }

    // Horizontal resizers mousedown (using event delegation on bottom bar)
    bottomBar.addEventListener('mousedown', function (e) {
        if (!e.target.classList.contains('section-resizer')) return;

        var resizerEl = e.target;
        resizeState.active = true;
        resizeState.type = 'horizontal';
        resizeState.startX = e.clientX;
        resizeState.leftSection = resizerEl.previousElementSibling;
        resizeState.rightSection = resizerEl.nextElementSibling;

        // Skip past other resizers
        while (resizeState.rightSection && resizeState.rightSection.classList.contains('section-resizer')) {
            resizeState.rightSection = resizeState.rightSection.nextElementSibling;
        }

        if (resizeState.leftSection && resizeState.rightSection) {
            resizeState.leftStartWidth = resizeState.leftSection.offsetWidth;
            resizeState.rightStartWidth = resizeState.rightSection.offsetWidth;
        }

        document.body.style.cursor = 'col-resize';
        e.preventDefault();
    });

    // Single document-level mousemove handler
    document.addEventListener('mousemove', function (e) {
        if (!resizeState.active) return;

        if (resizeState.type === 'vertical') {
            var dy = resizeState.startY - e.clientY;
            var newHeight = Math.max(80, Math.min(window.innerHeight - 100, resizeState.startHeight + dy));
            var heightPercent = (newHeight / window.innerHeight) * 100;
            bottomBar.style.height = heightPercent + '%';
            if (vertResizer) vertResizer.style.bottom = heightPercent + '%';

            // Update blocks container height
            document.querySelectorAll('.blocks-container').forEach(function (c) {
                c.style.height = (100 - heightPercent) + '%';
            });

            // Redraw dataflow edges
            requestAnimationFrame(function () { redrawDataflowEdges(true); });
        } else if (resizeState.type === 'horizontal') {
            if (!resizeState.leftSection || !resizeState.rightSection) return;

            var dx = e.clientX - resizeState.startX;
            var newLeftWidth = Math.max(100, resizeState.leftStartWidth + dx);
            var newRightWidth = Math.max(100, resizeState.rightStartWidth - dx);
            resizeState.leftSection.style.width = newLeftWidth + 'px';
            resizeState.rightSection.style.width = newRightWidth + 'px';
            resizeState.leftSection.style.flex = 'none';
            resizeState.rightSection.style.flex = 'none';

            // Redraw dataflow edges if resizing affects that section
            if (resizeState.leftSection.id === 'dataflow-section' ||
                resizeState.rightSection.id === 'dataflow-section') {
                requestAnimationFrame(function () { redrawDataflowEdges(true); });
            }
        }
    });

    // Single document-level mouseup handler
    document.addEventListener('mouseup', function () {
        if (resizeState.active) {
            resizeState.active = false;
            resizeState.type = null;
            document.body.style.cursor = '';
        }
    });
}
