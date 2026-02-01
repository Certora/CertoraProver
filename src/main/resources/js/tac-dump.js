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

var showUcCmdsOnly = function() {
    highlightActiveButton(this)
    setDisplayClass(this, "uc-only")
}

var showCmdsCloseToUc = function() {
    highlightActiveButton(this)
    setDisplayClass(this, "close-to-uc")
}

var showAllCmds = function() {
    highlightActiveButton(this)
    setDisplayClass(this, "all");
}

// ============================================================================
// State Variables
// ============================================================================

var highlightedAnchor = null;
var highlightedDef = null;
var highlightedUseCls = null;
var highlightedIntFun = null;
var lastSvgEventListener = null;
var currentSVG = "0";

// Dataflow state (dataflowMap is injected by Kotlin before this script loads)
var highlightedInputs = [];
var highlightedOutputs = [];
var dataflowGraph = {
    selectedVars: [],  // Variables explicitly clicked
    focusVar: null     // Currently focused variable
};

// Layout cache for dataflow graph
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
    Object.keys(cexValues).forEach(function(varName) {
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
// Note: Same element IDs can appear in different sub-graphs, so we search each container.
function findGraphWithDef(varName) {
    var containers = getAllContainers();
    for (var i = 0; i < containers.length; i++) {
        if (containers[i].querySelector('#def_' + CSS.escape(varName))) {
            return containers[i].id.replace("blocksAndEdges", "");
        }
    }
    return null;
}

// Set background color on element if it exists
function setBackground(el, color) {
    if (el) el.style.backgroundColor = color;
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

// Apply operation across all containers for a variable
function forEachContainerWithVar(varName, callback) {
    var containers = getAllContainers();
    for (var c = 0; c < containers.length; c++) {
        var el = containers[c].querySelector('#def_' + CSS.escape(varName));
        if (el) callback(el, containers[c]);
    }
}

// ============================================================================
// Browser History Navigation Support
// ============================================================================

function pushNavigationState() {
    var hash = currentSVG !== "0" ? '#svg=' + currentSVG : window.location.pathname;
    history.pushState({ svg: currentSVG }, '', hash);
}

function restoreNavigationState(state) {
    if (!state) {
        var hash = window.location.hash;
        var svg = "0";
        if (hash && hash.indexOf('svg=') >= 0) {
            svg = hash.split('svg=')[1].split('&')[0];
        }
        state = { svg: svg };
    }
    if (state.svg && state.svg !== currentSVG) {
        switchToGraph(state.svg, false);
    }
}

window.addEventListener('popstate', function(event) {
    restoreNavigationState(event.state);
});

window.addEventListener('DOMContentLoaded', function() {
    if (window.location.hash) {
        restoreNavigationState(null);
    }
});

// ============================================================================
// Graph Switching
// ============================================================================

// Core function to switch between graphs
function switchToGraph(newSvgId, pushHistory) {
    toggleVisibility(currentSVG, newSvgId, "svgOf", false);
    toggleVisibility(currentSVG, newSvgId, "blocksAndEdges", false);
    toggleVisibility(currentSVG, newSvgId, "mag_", false);
    toggleVisibility(currentSVG, newSvgId, "successorMap", true);
    currentSVG = newSvgId;
    if (pushHistory) pushNavigationState();
}

function toggleSVG(svgId) {
    switchToGraph(svgId, true);

    // Handle SVG pan-zoom
    var currentSuffix = currentSVG !== "0" ? currentSVG : "";
    var newSuffix = svgId !== "0" ? svgId : "";
    var pannedCurrent = document.getElementById("theSVG" + currentSuffix);
    var pannedNew = document.getElementById("theSVG" + newSuffix);

    if (pannedCurrent) svgPanZoom(pannedCurrent).destroy();
    if (pannedNew) {
        lastSvgEventListener = svgPanZoom('#theSVG' + newSuffix, {
            zoomEnabled: true,
            controlIconsEnabled: true,
            fit: true,
            center: true
        });
    }
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

function clearAnchorHighlight() {
    if (!highlightedAnchor) return;

    // Clear block/edge highlights across ALL containers (not just current)
    // because the highlight might be in a different graph than the current one
    var containers = getAllContainers();
    for (var c = 0; c < containers.length; c++) {
        var block = containers[c].querySelector("#block" + CSS.escape(highlightedAnchor));
        if (block) {
            setBackground(block, "white");
            setBackground(containers[c].querySelector("#edgeS" + CSS.escape(highlightedAnchor)), "white");
            setBackground(containers[c].querySelector("#edgeT" + CSS.escape(highlightedAnchor)), "white");
            setBackground(containers[c].querySelector("#edgeP" + CSS.escape(highlightedAnchor)), "white");
        }
    }

    // Clear any highlighted SVG nodes across ALL graphs
    clearAllSvgHighlights();
    highlightedAnchor = null;
}

function clearAllSvgHighlights() {
    // Find all SVG nodes with yellow fill (our highlight color) and reset them
    var svgContainers = document.querySelectorAll('[id^="svgOf"]');
    svgContainers.forEach(function(container) {
        // Look for polygon/ellipse elements with yellow fill
        var highlighted = container.querySelectorAll('[fill="yellow"]');
        highlighted.forEach(function(el) {
            // Restore original appearance from data attributes, or use defaults
            el.setAttribute("fill", el.getAttribute("data-orig-fill") || "white");
            el.setAttribute("stroke-width", el.getAttribute("data-orig-stroke-width") || "1px");
            el.setAttribute("stroke-dasharray", el.getAttribute("data-orig-stroke-dasharray") || "none");
        });
    });
}

function clearDefHighlight() {
    if (!highlightedDef) return;

    var containers = getAllContainers();
    for (var c = 0; c < containers.length; c++) {
        var defEl = containers[c].querySelector('#' + CSS.escape(highlightedDef));
        if (defEl) defEl.style.removeProperty('background-color');

        var useElements = containers[c].getElementsByClassName(highlightedUseCls);
        for (var i = 0; i < useElements.length; i++) {
            useElements[i].style.removeProperty('background-color');
        }
    }

    // Also clear in the CEX (counterexample) panel
    var cexPanel = document.getElementById("cex");
    if (cexPanel) {
        var cexUseElements = cexPanel.getElementsByClassName(highlightedUseCls);
        for (var i = 0; i < cexUseElements.length; i++) {
            cexUseElements[i].style.removeProperty('background-color');
        }
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
    allVars.forEach(function(varName) {
        forEachContainerWithVar(varName, function(el) {
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

function highlightAnchor(anchorId) {
    var messagesDiv = document.getElementById("messages");
    messagesDiv.innerHTML = "";
    clearAnchorHighlight();
    highlightedAnchor = anchorId;

    var newBlock = findInCurrentContainer("block" + anchorId);
    var newEdgeS = findInCurrentContainer("edgeS" + anchorId);
    var newEdgeT = findInCurrentContainer("edgeT" + anchorId);
    var newEdgeP = findInCurrentContainer("edgeP" + anchorId);
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
        setBackground(newEdgeS, "yellow");
        setBackground(newEdgeT, "yellow");
        setBackground(newEdgeP, "yellow");
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

    if (newEdgeS) newEdgeS.scrollIntoView();
    if (newEdgeP) newEdgeP.scrollIntoView();
}

// Core definition highlighting (without dataflow navigation)
function doHighlightDef(def) {
    clearDefHighlight();
    highlightedDef = "def_" + def;
    highlightedUseCls = "use_" + def;

    var containers = getAllContainers();
    for (var c = 0; c < containers.length; c++) {
        var defEl = containers[c].querySelector('#def_' + CSS.escape(def));
        if (defEl) defEl.style.backgroundColor = "yellow";

        var useElements = containers[c].getElementsByClassName(highlightedUseCls);
        for (var i = 0; i < useElements.length; i++) {
            useElements[i].style.backgroundColor = "yellow";
        }
    }

    // Also highlight in the CEX (counterexample) panel
    var cexPanel = document.getElementById("cex");
    if (cexPanel) {
        var cexUseElements = cexPanel.getElementsByClassName(highlightedUseCls);
        for (var i = 0; i < cexUseElements.length; i++) {
            cexUseElements[i].style.backgroundColor = "yellow";
        }
    }

    var defInCurrentGraph = findInCurrentContainer(highlightedDef);
    if (defInCurrentGraph) {
        defInCurrentGraph.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    }
}

function highlightDef(def) {
    var messagesDiv = document.getElementById("messages");
    messagesDiv.innerHTML = "";
    messagesDiv.style.backgroundColor = "";

    doHighlightDef(def);

    // Check if def was found
    var defFound = getAllContainers().length > 0 &&
        Array.prototype.some.call(getAllContainers(), function(c) {
            return c.querySelector('#def_' + CSS.escape(def));
        });

    if (!defFound) {
        messagesDiv.innerHTML = "def " + def + " does not exist in any method.";
        messagesDiv.style.backgroundColor = "red";
    }

    // Handle dataflow graph
    var isAlreadySelected = dataflowGraph.selectedVars.indexOf(def) >= 0;
    var isHighlighted = highlightedInputs.indexOf(def) >= 0 || highlightedOutputs.indexOf(def) >= 0;
    var isConnected = isConnectedToGraph(def);

    if (isAlreadySelected || isHighlighted || isConnected) {
        // Add to graph (or just change focus if already selected)
        addToDataflowGraph(def);
    } else {
        // Disconnected - clear and start fresh
        clearDataflowGraph();
        addToDataflowGraph(def);
    }
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
function clearDataflowGraph() {
    dataflowGraph.selectedVars = [];
    dataflowGraph.focusVar = null;
    clearDataflowHighlights();
    renderDataflowGraph();
}

// Highlight inputs/outputs of focused variable in the code
function showDataflowHighlights(varName) {
    clearDataflowHighlights();
    var info = dataflowMap[varName];
    if (!info) return;

    var colors = getDataflowColors();
    function highlight(vars, color, arr) {
        (vars || []).forEach(function(v) {
            var el = findInCurrentContainer("def_" + v);
            if (el) {
                el.style.outline = "2px solid " + color;
                el.style.outlineOffset = "1px";
                arr.push(v);
            }
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

// Internal: actual layout computation (BFS from focus)
function doComputeGraphLayout() {
    var focus = dataflowGraph.focusVar;
    var selectedSet = new Set(dataflowGraph.selectedVars);
    var nodeLayer = {};
    var visited = new Set();

    // BFS from focus
    nodeLayer[focus] = 0;
    visited.add(focus);
    var queue = [{ v: focus, layer: 0 }];

    while (queue.length > 0) {
        var cur = queue.shift();
        var v = cur.v;
        var layer = cur.layer;
        var vInfo = dataflowMap[v] || { inputs: [], outputs: [] };

        // Check inputs (upstream, layer - 1)
        (vInfo.inputs || []).forEach(function(inp) {
            if (!visited.has(inp) && selectedSet.has(inp)) {
                visited.add(inp);
                nodeLayer[inp] = layer - 1;
                queue.push({ v: inp, layer: layer - 1 });
            }
        });

        // Check outputs (downstream, layer + 1)
        (vInfo.outputs || []).forEach(function(out) {
            if (!visited.has(out) && selectedSet.has(out)) {
                visited.add(out);
                nodeLayer[out] = layer + 1;
                queue.push({ v: out, layer: layer + 1 });
            }
        });

        // Also check reverse direction (in case dataflowMap is asymmetric)
        selectedSet.forEach(function(sel) {
            if (visited.has(sel)) return;
            var selInfo = dataflowMap[sel] || { inputs: [], outputs: [] };

            // sel is upstream of v if v is in sel's outputs
            if (selInfo.outputs && selInfo.outputs.indexOf(v) >= 0) {
                visited.add(sel);
                nodeLayer[sel] = layer - 1;
                queue.push({ v: sel, layer: layer - 1 });
            }
            // sel is downstream of v if v is in sel's inputs
            else if (selInfo.inputs && selInfo.inputs.indexOf(v) >= 0) {
                visited.add(sel);
                nodeLayer[sel] = layer + 1;
                queue.push({ v: sel, layer: layer + 1 });
            }
        });
    }

    // Place any unvisited selected vars at far left
    var minLayer = Math.min.apply(null, Object.values(nodeLayer).concat([0]));
    dataflowGraph.selectedVars.forEach(function(v) {
        if (nodeLayer[v] === undefined) {
            minLayer--;
            nodeLayer[v] = minLayer;
        }
    });

    // Add transient nodes (inputs/outputs of focus not in selectedVars)
    var focusInfo = dataflowMap[focus] || { inputs: [], outputs: [] };
    (focusInfo.inputs || []).forEach(function(inp) {
        if (nodeLayer[inp] === undefined) nodeLayer[inp] = -1;
    });
    (focusInfo.outputs || []).forEach(function(out) {
        if (nodeLayer[out] === undefined) nodeLayer[out] = 1;
    });

    // Adjust layers to ensure proper left-to-right flow
    // If there's an edge a -> b, then layer[b] must be > layer[a]
    // This handles diamond patterns like x->y, y->z, x->z where z should be right of y
    var visibleNodes = Object.keys(nodeLayer);
    var changed = true;
    var maxIterations = visibleNodes.length + 1; // Prevent infinite loops
    while (changed && maxIterations-- > 0) {
        changed = false;
        visibleNodes.forEach(function(v) {
            var vInfo = dataflowMap[v] || { inputs: [], outputs: [] };
            (vInfo.outputs || []).forEach(function(out) {
                if (nodeLayer[out] !== undefined && nodeLayer[out] <= nodeLayer[v]) {
                    nodeLayer[out] = nodeLayer[v] + 1;
                    changed = true;
                }
            });
        });
    }

    // Build layers
    var layers = {};
    Object.keys(nodeLayer).forEach(function(v) {
        var l = nodeLayer[v];
        if (!layers[l]) layers[l] = [];
        layers[l].push(v);
    });

    // Compute edges from dataflowMap
    var edges = [];
    var edgeSet = new Set();
    Object.keys(nodeLayer).forEach(function(v) {
        var vInfo = dataflowMap[v] || { inputs: [], outputs: [] };
        (vInfo.outputs || []).forEach(function(out) {
            if (nodeLayer[out] !== undefined) {
                var key = v + "→" + out;
                if (!edgeSet.has(key)) {
                    edgeSet.add(key);
                    edges.push({ from: v, to: out });
                }
            }
        });
    });

    return { layers: layers, nodeLayer: nodeLayer, edges: edges };
}

// Clear SVG edges while preserving the defs element (arrow markers)
function clearSvgEdges(edgesSvg) {
    // Remove all children except defs
    var children = Array.from(edgesSvg.children);
    children.forEach(function(child) {
        if (child.tagName.toLowerCase() !== 'defs') {
            edgesSvg.removeChild(child);
        }
    });
}

// Redraw dataflow edges (uses cached layout)
function redrawDataflowEdges() {
    if (!dataflowGraph.focusVar) return;

    var container = document.getElementById("dataflow-graph-container");
    var edgesSvg = document.getElementById("dataflow-edges");
    var nodesDiv = document.getElementById("dataflow-nodes");
    if (!container || !edgesSvg || !nodesDiv) return;

    clearSvgEdges(edgesSvg);
    var layout = computeGraphLayout();
    drawGraphEdges(container, nodesDiv, edgesSvg, layout);
}

// Render the graph
function renderDataflowGraph() {
    var container = document.getElementById("dataflow-graph-container");
    var nodesDiv = document.getElementById("dataflow-nodes");
    var edgesSvg = document.getElementById("dataflow-edges");
    var hint = document.getElementById("dataflow-hint");
    var clearBtn = document.getElementById("dataflow-clear-btn");

    if (!container || !nodesDiv || !edgesSvg) return;

    nodesDiv.innerHTML = "";
    clearSvgEdges(edgesSvg);

    if (!dataflowGraph.focusVar) {
        if (hint) hint.style.display = "block";
        if (clearBtn) clearBtn.style.display = "none";
        return;
    }

    if (hint) hint.style.display = "none";
    if (clearBtn) clearBtn.style.display = "inline-block";

    var layout = computeGraphLayout();
    var layerKeys = Object.keys(layout.layers).map(Number).sort(function(a, b) { return a - b; });
    if (layerKeys.length === 0) return;

    var selectedSet = new Set(dataflowGraph.selectedVars);

    // Create nodes
    layerKeys.forEach(function(layerIdx) {
        var vars = layout.layers[layerIdx];
        var layerDiv = document.createElement("div");
        layerDiv.className = "dataflow-layer";

        vars.forEach(function(varName) {
            var nodeDiv = document.createElement("div");
            var isFocus = varName === dataflowGraph.focusVar;
            var isSelected = selectedSet.has(varName);
            var nodeClass = "dataflow-node";

            if (isFocus) nodeClass += " focus";
            else if (isSelected) nodeClass += " selected";
            else if (layerIdx < 0) nodeClass += " input";
            else nodeClass += " output";

            nodeDiv.className = nodeClass;
            nodeDiv.textContent = varName;
            nodeDiv.setAttribute("data-var", varName);
            nodeDiv.onclick = function() { onGraphNodeClick(varName); };

            // Add mouse-following tooltip with counter-example value if available
            var cexValue = getCexValue(varName);
            if (cexValue) {
                nodeDiv.addEventListener('mouseenter', function() {
                    var tooltip = document.getElementById('dataflow-tooltip');
                    if (tooltip) {
                        tooltip.textContent = varName + ' = ' + cexValue;
                        tooltip.style.display = 'block';
                    }
                });
                nodeDiv.addEventListener('mouseleave', function() {
                    var tooltip = document.getElementById('dataflow-tooltip');
                    if (tooltip) tooltip.style.display = 'none';
                });
                nodeDiv.addEventListener('mousemove', function(e) {
                    var tooltip = document.getElementById('dataflow-tooltip');
                    if (tooltip) {
                        tooltip.style.left = (e.clientX + 10) + 'px';
                        tooltip.style.top = (e.clientY + 10) + 'px';
                    }
                });
            }

            layerDiv.appendChild(nodeDiv);
        });
        nodesDiv.appendChild(layerDiv);
    });

    // Draw edges after layout
    requestAnimationFrame(function() {
        drawGraphEdges(container, nodesDiv, edgesSvg, layout);
    });
}

function drawGraphEdges(container, nodesDiv, edgesSvg, layout) {
    var containerRect = container.getBoundingClientRect();
    var scrollLeft = container.scrollLeft;
    var scrollTop = container.scrollTop;
    var nodeRects = {};

    nodesDiv.querySelectorAll(".dataflow-node").forEach(function(el) {
        var v = el.getAttribute("data-var");
        var rect = el.getBoundingClientRect();
        nodeRects[v] = {
            cx: rect.left - containerRect.left + scrollLeft + rect.width / 2,
            cy: rect.top - containerRect.top + scrollTop + rect.height / 2,
            w: rect.width, h: rect.height
        };
    });

    // Size SVG to full scrollable area
    var svgWidth = Math.max(nodesDiv.scrollWidth, containerRect.width);
    var svgHeight = Math.max(nodesDiv.scrollHeight, containerRect.height);
    edgesSvg.setAttribute("width", svgWidth);
    edgesSvg.setAttribute("height", svgHeight);

    // Get colors from CSS custom properties
    var colors = getDataflowColors();

    layout.edges.forEach(function(edge) {
        var from = nodeRects[edge.from];
        var to = nodeRects[edge.to];
        if (!from || !to) return;

        var dx = to.cx - from.cx;
        var dy = to.cy - from.cy;
        var dist = Math.sqrt(dx * dx + dy * dy);
        if (dist === 0) return;

        // Shorten line to node edges
        var x1 = from.cx + (dx / dist) * (from.w / 2 + 2);
        var y1 = from.cy + (dy / dist) * (from.h / 2 + 2);
        var x2 = to.cx - (dx / dist) * (to.w / 2 + 8);
        var y2 = to.cy - (dy / dist) * (to.h / 2 + 8);

        // Color by relationship to focus:
        // - Blue: edge target is focus (input to focus)
        // - Green: edge source is focus (output from focus)
        // - Gray: other edges
        var focus = dataflowGraph.focusVar;
        var edgeColor, markerEnd;
        if (edge.to === focus) {
            edgeColor = colors.input;
            markerEnd = "url(#dataflow-arrow-input)";
        } else if (edge.from === focus) {
            edgeColor = colors.output;
            markerEnd = "url(#dataflow-arrow-output)";
        } else {
            edgeColor = "#888";
            markerEnd = "url(#dataflow-arrow-neutral)";
        }

        var line = document.createElementNS("http://www.w3.org/2000/svg", "line");
        line.setAttribute("x1", x1);
        line.setAttribute("y1", y1);
        line.setAttribute("x2", x2);
        line.setAttribute("y2", y2);
        line.setAttribute("stroke", edgeColor);
        line.setAttribute("stroke-width", "2");
        line.setAttribute("marker-end", markerEnd);
        edgesSvg.appendChild(line);
    });
}

function onGraphNodeClick(varName) {
    var graphId = findGraphWithDef(varName);
    if (graphId && graphId !== currentSVG) toggleSVG(graphId);
    doHighlightDef(varName);
    addToDataflowGraph(varName);
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
        navigator.clipboard.writeText(text).then(function() {
            tooltiptext.classList.add('copied');
            setTimeout(function() {
                tooltiptext.classList.remove('copied');
            }, COPY_FEEDBACK_DURATION);
        });
    }
}

// ============================================================================
// Initialization
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
    var tooltips_spans = document.getElementsByClassName('tooltip');
    for (var i = 0; i < tooltips_spans.length; i++) {
        var me = tooltips_spans[i];
        var tooltiptext = me.querySelector('.tooltiptext');

        me.addEventListener("mouseenter", function() {
            hoveredTooltipSpan = this;
            showTooltip(this);
        });

        me.addEventListener("mouseleave", function() {
            hoveredTooltipSpan = null;
            hideTooltip(this);
        });

        if (tooltiptext) {
            tooltiptext.addEventListener("click", function(e) {
                e.stopPropagation();
                copyToClipboard(this);
            });
        }
    }

    // Keyboard shortcut to copy tooltip content while hovering
    // Press 'c' to copy the tooltip text of the currently hovered element
    document.addEventListener("keydown", function(e) {
        if (e.key === 'c' && !e.ctrlKey && !e.metaKey && !e.altKey) {
            // Only trigger if not typing in an input field
            var tagName = document.activeElement.tagName.toLowerCase();
            if (tagName !== 'input' && tagName !== 'textarea') {
                copyActiveTooltip();
            }
        }
    });

    // Initialize SVG pan-zoom for the main graph
    lastSvgEventListener = svgPanZoom('#theSVG', {
        zoomEnabled: true,
        controlIconsEnabled: true,
        fit: true,
        center: true
    });

    // Set up dataflow graph panning
    var dfContainer = document.getElementById("dataflow-graph-container");
    if (dfContainer) {
        var isPanning = false;
        var panStart = { x: 0, y: 0, scrollLeft: 0, scrollTop: 0 };

        dfContainer.addEventListener('mousedown', function(e) {
            if (e.target.classList.contains('dataflow-node')) return;
            isPanning = true;
            panStart = {
                x: e.clientX,
                y: e.clientY,
                scrollLeft: dfContainer.scrollLeft,
                scrollTop: dfContainer.scrollTop
            };
            dfContainer.style.cursor = 'grabbing';
            e.preventDefault();
        });

        document.addEventListener('mousemove', function(e) {
            if (!isPanning) return;
            var dx = e.clientX - panStart.x;
            var dy = e.clientY - panStart.y;
            dfContainer.scrollLeft = panStart.scrollLeft - dx;
            dfContainer.scrollTop = panStart.scrollTop - dy;
        });

        document.addEventListener('mouseup', function() {
            if (isPanning) {
                isPanning = false;
                dfContainer.style.cursor = 'grab';
            }
        });

        // Redraw edges on scroll
        dfContainer.addEventListener('scroll', function() {
            redrawDataflowEdges();
        });
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
        vertResizer.addEventListener('mousedown', function(e) {
            resizeState.active = true;
            resizeState.type = 'vertical';
            resizeState.startY = e.clientY;
            resizeState.startHeight = bottomBar.offsetHeight;
            document.body.style.cursor = 'row-resize';
            e.preventDefault();
        });
    }

    // Horizontal resizers mousedown (using event delegation on bottom bar)
    bottomBar.addEventListener('mousedown', function(e) {
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
    document.addEventListener('mousemove', function(e) {
        if (!resizeState.active) return;

        if (resizeState.type === 'vertical') {
            var dy = resizeState.startY - e.clientY;
            var newHeight = Math.max(80, Math.min(window.innerHeight - 100, resizeState.startHeight + dy));
            var heightPercent = (newHeight / window.innerHeight) * 100;
            bottomBar.style.height = heightPercent + '%';
            if (vertResizer) vertResizer.style.bottom = heightPercent + '%';

            // Update blocks container height
            document.querySelectorAll('.blocks-container').forEach(function(c) {
                c.style.height = (100 - heightPercent) + '%';
            });

            // Redraw dataflow edges
            requestAnimationFrame(redrawDataflowEdges);
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
                requestAnimationFrame(redrawDataflowEdges);
            }
        }
    });

    // Single document-level mouseup handler
    document.addEventListener('mouseup', function() {
        if (resizeState.active) {
            resizeState.active = false;
            resizeState.type = null;
            document.body.style.cursor = '';
        }
    });
}
