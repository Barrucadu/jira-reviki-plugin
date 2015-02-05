package net.hillsdon.reviki.jira.renderer;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.regex.Pattern;

import com.atlassian.jira.component.ComponentAccessor;
import com.google.common.base.Optional;

import net.hillsdon.reviki.web.urls.Configuration;
import net.hillsdon.reviki.vc.SimplePageStore;
import net.hillsdon.reviki.vc.impl.DummyPageStore;
import net.hillsdon.reviki.web.urls.InterWikiLinker;
import net.hillsdon.reviki.web.urls.InternalLinker;
import net.hillsdon.reviki.web.urls.SimpleWikiUrls;
import net.hillsdon.reviki.web.urls.UnknownWikiException;
import net.hillsdon.reviki.wiki.renderer.HtmlRenderer;
import net.hillsdon.reviki.wiki.renderer.creole.LinkResolutionContext;

/**
 * A simple interface to Reviki's HTML rendering capabilities.
 *
 * @author msw
 */
public final class JiraRevikiRenderer {
  /** Match Confluence-style links in single square brackets.*/
  private static final Pattern confluenceLinks = Pattern.compile("([^\\[]|^)\\[([^\\\\,\\[\\]<>|]+)(?:(\\|)([^\\\\,\\[\\]<>]+))?\\]([^\\]]|$)");

  /** Replacement text to turn Confluence-style links into Reviki-style links. */
  private static final String revikiReplacement = "$1[[$4$3$2]]$5";

  /** Render Reviki markup to HTML, complete with link handling. */
  private static final String JIRA_PATH = ComponentAccessor.getApplicationProperties().getString("jira.baseurl");

  /** Plugin configuration. */
  private final RevikiPluginConfiguration _pluginSettings;

  public JiraRevikiRenderer(final RevikiPluginConfiguration pluginSettings) {
    _pluginSettings = pluginSettings;
  }

  /**
   * Render some markup to HTML.
   */
  public String render(final String text) {
    if (text == null) {
      return "";
    }

    HtmlRenderer renderer = makeRendererWith(_pluginSettings.interWikiLinks());

    String contents = text;

    // First fix any Confluence-style links for backwards-compatibility.
    if (_pluginSettings.convertConfluenceLinks()) {
      contents = confluenceToReviki(text);
    }

    // Try rendering it, and return the original markup if we fail.
    String out = text;
    Optional<String> rendered = renderer.render(contents);
    if (rendered.isPresent()) {
      // Use Confluence styling on the tables.
      out = rendered.get();
      out = out.replace("<table " + HtmlRenderer.CSS_CLASS_ATTR, "<table class=\"confluenceTable\"");
      out = out.replace("<th " + HtmlRenderer.CSS_CLASS_ATTR, "<th class=\"confluenceTh\"");
      out = out.replace("<td " + HtmlRenderer.CSS_CLASS_ATTR, "<td class=\"confluenceTd\"");

      // Remove the JHighlight comment, as it adds an extra newline to a pre.
      out = out.replace("<!--  : generated by JHighlight v1.0 (http://jhighlight.dev.java.net) -->\n", "");

      // Wrap up in a div so we can easily override more specific CSS
      out = "<div class=\"reviki\">" + out + "</div>";
    }

    return out;
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
   * Links to WikiWords make no sense in JIRA.
   * You can link to issues: NAME-12345
   * Check for the presence of '-' in the name?  Crude but effective?
   */
  private static class JiraInternalLinker extends InternalLinker {
    private final String _userUrlBase;

    public JiraInternalLinker(final SimpleWikiUrls urls, final String userUrlBase) {
      super(urls);

      _userUrlBase = userUrlBase;
    }

    public URI uri(final String pageName) throws UnknownWikiException, URISyntaxException {
      if (pageName.startsWith("~")) {
        return new URI(_userUrlBase + pageName.substring(1));
      }
      else if (pageName.contains("-")) {
        return super.uri(pageName);
      }
      else {
        return null;
      }
    }
  }

  private static class JiraWikiConfiguration implements Configuration {
    private final InterWikiLinker _wikilinker;

    public JiraWikiConfiguration(final InterWikiLinker wikilinker) {
      _wikilinker = wikilinker;
    }

    public InterWikiLinker getInterWikiLinker() {
      return _wikilinker;
    }
  }

  /**
   * Construct a renderer with the given interwiki links.
   */
  private static HtmlRenderer makeRendererWith(final Map<String, String> interWikilinks) {
    // Have all internal relative links start from the browse directory.
    SimpleWikiUrls wikiUrls = SimpleWikiUrls.RELATIVE_TO.apply(JIRA_PATH + "/browse");
    String userUrlBase = JIRA_PATH + "/secure/ViewProfile.jspa?name="; // SimpleWikiUrls is too magic WRT the query parameters
    InternalLinker linker = new JiraInternalLinker(wikiUrls, userUrlBase);

    // Add the known wikis
    InterWikiLinker wikilinker = new InterWikiLinker();

    for (String prefix : interWikilinks.keySet()) {
      wikilinker.addWiki(prefix, interWikilinks.get(prefix));
    }

    // We don't know of any other pages.
    SimplePageStore pageStore = new DummyPageStore();

    return new HtmlRenderer(new LinkResolutionContext(linker, wikilinker, new JiraWikiConfiguration(wikilinker), pageStore));
  }
}
