package net.hillsdon.reviki.jira.renderer;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import com.atlassian.jira.issue.RendererManager;
import com.atlassian.jira.issue.fields.renderer.IssueRenderContext;
import com.atlassian.sal.api.pluginsettings.PluginSettings;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.base.Optional;

import net.hillsdon.reviki.vc.PageInfo;
import net.hillsdon.reviki.vc.SimplePageStore;
import net.hillsdon.reviki.vc.impl.DummyPageStore;
import net.hillsdon.reviki.vc.impl.PageInfoImpl;
import net.hillsdon.reviki.web.urls.InterWikiLinker;
import net.hillsdon.reviki.web.urls.InternalLinker;
import net.hillsdon.reviki.web.urls.URLOutputFilter;
import net.hillsdon.reviki.web.urls.SimpleWikiUrls;
import net.hillsdon.reviki.wiki.renderer.HtmlRenderer;
import net.hillsdon.reviki.wiki.renderer.creole.SimpleAnchors;
import net.hillsdon.reviki.wiki.renderer.creole.SimpleImages;
import net.hillsdon.reviki.wiki.renderer.creole.LinkPartsHandler;
import net.hillsdon.reviki.wiki.renderer.creole.LinkResolutionContext;
import net.hillsdon.reviki.wiki.renderer.macro.Macro;

/**
 * A simple interface to Reviki's HTML rendering capabilities.
 *
 * @author msw
 */
public final class JiraRevikiRenderer {
  public static final String CONFLUENCE_LINK_CONFIG_KEY = "convertConfluence";

  /** Match Confluence-style links in single square brackets. */
  private static final Pattern confluenceLinks = Pattern.compile("([^\\[]|^)(\\[[~@]*[^\\\\,\\[\\]]+?\\])([^\\]]|$)");

  /** Replacement text to turn Confluence-style links into Reviki-style links. */
  private static final String revikiReplacement = "$1[$2]$3";

  /** Render Reviki markup to HTML, complete with link handling. */
  private static final HtmlRenderer _renderer;

  /** Plugin configuration. */
  private final PluginSettings _pluginSettings;

  /** Reference to the renderer manager. */
  private final RendererManager _rendererManager;

  static {
    // Have all internal relative links start from /jira/browse/
    SimpleWikiUrls wikiUrls = SimpleWikiUrls.RELATIVE_TO.apply("/jira/browse/");
    InternalLinker linker = new InternalLinker(wikiUrls);

    // We know of no other wikis.
    InterWikiLinker wikilinker = new InterWikiLinker();

    // Or any pages.
    SimplePageStore pageStore = new DummyPageStore();

    LinkResolutionContext resolver = new LinkResolutionContext(linker, wikilinker, pageStore);

    // Render links as links, and images as images.
    LinkPartsHandler linkHandler = new SimpleAnchors(resolver);
    LinkPartsHandler imageHandler = new SimpleImages(resolver);

    // We have no macros, either.
    Supplier<List<Macro>> macros = Suppliers.ofInstance((List<Macro>) new LinkedList<Macro>());

    // Finally, construct the renderer.
    _renderer = new HtmlRenderer(pageStore, linkHandler, imageHandler, macros);
  }

  public JiraRevikiRenderer(final PluginSettings pluginSettings, final RendererManager rendererManager) {
    _pluginSettings = pluginSettings;
    _rendererManager = rendererManager;
  }

  /**
   * Render some markup to HTML.
   */
  public String render(final String text, final IssueRenderContext ctx) {
    String contents = text;

    // First fix any Confluence-style links for backwards-compatibility.
    //
    // Now, you might think it would be good to use a Boolean here. After all,
    // put/get use Object, rather than any more specific type, so clearly I
    // could do that, right? Nope. JiraPluginSettings extends
    // AbstractStringPluginSettings, which only accepts Strings. Anything else
    // raises an IllegalArgumentException.
    if (getProperty(CONFLUENCE_LINK_CONFIG_KEY, "y").equals("y")) {
      contents = confluenceToReviki(text);
    }

    // Then construct a dummy page, containing just the markup we want to
    // render.
    PageInfo page = new PageInfoImpl("", "", contents, Collections.<String, String> emptyMap());

    // Try rendering it, and return the original markup if we fail.
    Optional<String> rendered = _renderer.render(page, URLOutputFilter.NULL);
    return rendered.isPresent() ? rendered.get() : text;
  }

  /**
   * Convert Confluence-style links ("[FOO-1]") to Reviki-style links
   * ("[[FOO-1]]"), this allows backwards compatibility in linking to issues.
   */
  private static String confluenceToReviki(final String text) {
    // Need to run the regex twice because of overlapping matches.
    //
    // eg: "[FOO-1] [FOO-1] [FOO-1]" - just replacing once results in
    // "[[FOO-1]] [FOO-1] [[FOO-1]]"

    String first = confluenceLinks.matcher(text).replaceAll(revikiReplacement);
    return confluenceLinks.matcher(first).replaceAll(revikiReplacement);
  }

  /**
   * Get a value from the settings with a default.
   */
  @SuppressWarnings("unchecked")
  public <T> T getProperty(String name, T def) {
    Object out = _pluginSettings.get(name);
    return (out == null) ? def : (T) out;
  }
}
