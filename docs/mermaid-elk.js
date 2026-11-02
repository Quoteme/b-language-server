
/*
 * Appended to Mermaid's mermaid.min.js by gradle/dokka.init.gradle.kts, replacing the older Mermaid
 * that the Dokka Mermaid plugin bundles.
 *
 * Lays out every diagram with ELK, which routes edges at right angles and keeps their labels
 * apart. The plugin renders each diagram on page load; rendering waits until ELK is registered.
 */
(function () {
  var mermaid = globalThis.mermaid;
  var base = document.currentScript ? document.currentScript.src : location.href;
  var ready = import(new URL("mermaid-elk/mermaid-layout-elk.esm.min.mjs", base).href)
    .then(function (elk) {
      mermaid.registerLayoutLoaders(elk.default);
    })
    .catch(function (error) {
      console.error("ELK layout unavailable, using Mermaid's default layout", error);
    });

  // The plugin passes only the theme; the docs have one look, so it is fixed here as well.
  var initialize = mermaid.initialize.bind(mermaid);
  mermaid.initialize = function (config) {
    initialize(
      Object.assign({}, config, {
        theme: "default",
        // Lets a click on a sequence diagram participant open its menu of links to the docs.
        securityLevel: "loose",
        layout: "elk",
        elk: { mergeEdges: false, nodePlacementStrategy: "NETWORK_SIMPLEX" },
        // Wide enough for class names such as BDocumentService, which would otherwise break mid-word.
        flowchart: Object.assign({}, config && config.flowchart, { wrappingWidth: 240 }),
      }),
    );
  };

  var render = mermaid.render.bind(mermaid);
  mermaid.render = function () {
    var args = arguments;
    return ready.then(function () {
      return render.apply(null, args).then(function (result) {
        // Mermaid tags a sequence diagram's alt fragment "alt"; the docs call it "options", as each section is one.
        result.svg = result.svg.replace(/(<text[^>]*class="labelText"[^>]*>)alt(<\/text>)/g, "$1options$2");
        return result;
      });
    });
  };
})();
