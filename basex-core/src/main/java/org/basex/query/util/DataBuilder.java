package org.basex.query.util;

import static org.basex.util.Token.*;

import java.util.*;

import org.basex.core.*;
import org.basex.data.*;
import org.basex.query.iter.*;
import org.basex.query.util.DataFTBuilder.DataFTMarker;
import org.basex.query.util.ft.*;
import org.basex.query.util.list.*;
import org.basex.query.value.item.*;
import org.basex.query.value.node.*;
import org.basex.query.value.type.*;
import org.basex.util.*;

/**
 * Class for building memory-based database nodes.
 *
 * @author BaseX Team 2005-15, BSD License
 * @author Christian Gruen
 */
public final class DataBuilder {
  /** Target data instance. */
  private final MemData data;
  /** Full-text position data. */
  private DataFTBuilder ftbuilder;
  /** Index reference of marker element. */
  private int name;

  /**
   * Constructor.
   * @param data target data
   */
  public DataBuilder(final MemData data) {
    this.data = data;
  }

  /**
   * Attaches full-text position data.
   * @param nm name of marker element
   * @param pos full-text position data
   * @param len length of extract
   * @return self reference
   */
  public DataBuilder ftpos(final byte[] nm, final FTPosData pos, final int len) {
    ftbuilder = new DataFTBuilder(pos, len);
    name = data.elemNames.index(nm, null, false);
    return this;
  }

  /**
   * Fills the data instance with the specified node.
   * @param node node
   */
  public void build(final ANode node) {
    build(new ANodeList(node));
  }

  /**
   * Fills the data instance with the specified nodes.
   * @param nodes node list
   */
  public void build(final ANodeList nodes) {
    data.meta.update();
    int ds = data.meta.size;
    for(final ANode n : nodes) ds = addNode(n, ds, -1, null);
  }

  /**
   * Adds a fragment to a database instance.
   * Document nodes are ignored.
   * @param node node to be added
   * @param pre node position
   * @param par node parent
   * @param pNode parent of node to be added
   * @return pre value of next node
   */
  private int addNode(final ANode node, final int pre, final int par, final ANode pNode) {
    switch(node.nodeType()) {
      case DOC: return addDoc(node, pre);
      case ELM: return addElem(node, pre, par);
      case TXT: return addText(node, pre, par, pNode);
      case ATT: return addAttr(node, pre, par);
      case COM: return addComm(node, pre, par);
      // will always be processing instruction
      default:  return addPI(node, pre, par);
    }
  }

  /**
   * Adds a document node.
   * @param node node to be added
   * @param pre pre reference
   * @return pre value of next node
   */
  private int addDoc(final ANode node, final int pre) {
    final int ds = data.meta.size;
    final int s = size(node, false);
    data.doc(ds, s, node.baseURI());
    data.insert(ds);
    int p = pre + 1;
    final BasicNodeIter iter = node.children();
    for(ANode ch; (ch = iter.next()) != null;) p = addNode(ch, p, pre, null);
    if(s != p - pre) data.size(ds, Data.DOC, p - pre);
    return p;
  }

  /**
   * Adds an attribute.
   * @param node node to be added
   * @param pre pre reference
   * @param par parent reference
   * @return number of added nodes
   */
  private int addAttr(final ANode node, final int pre, final int par) {
    final int ds = data.meta.size;
    final QNm q = node.qname();
    final byte[] uri = q.uri();
    int u = 0;
    if(uri.length != 0) {
      if(par == -1) data.nspaces.add(ds, pre + 1, q.prefix(), uri, data);
      u = data.nspaces.uri(uri);
    }
    final int n = data.attrNames.index(q.string(), null, false);
    // usually, attributes don't have a namespace flag.
    // this is different here, because a stand-alone attribute has no parent element.
    data.attr(ds, pre - par, n, node.string(), u, par == -1 && u != 0);
    data.insert(ds);
    return pre + 1;
  }

  /**
   * Adds a text node.
   * @param node node to be added
   * @param pre pre reference
   * @param par parent reference
   * @param pNode parent node
   * @return pre value of next node
   */
  private int addText(final ANode node, final int pre, final int par, final ANode pNode) {
    // check full-text mode
    int dist = pre - par;
    final ArrayList<DataFTMarker> marks = ftbuilder != null ? ftbuilder.build(node) : null;
    if(marks == null) return pre + addText(node.string(), dist);

    // adopt namespace from parent
    ANode p = pNode;
    while(p != null) {
      final QNm n = p.qname();
      if(n != null && !n.hasPrefix()) break;
      p = p.parent();
    }
    final int u = p == null ? 0 : data.nspaces.uri(p.name(), true);

    int ts = marks.size();
    for(final DataFTMarker marker : marks) {
      if(marker.mark) {
        // open element
        data.elem(dist++, name, 1, 2, u, false);
        data.insert(data.meta.size);
        ts++;
      }
      addText(marker.token, marker.mark ? 1 : dist);
      dist++;
    }
    return pre + ts;
  }

  /**
   * Adds a text.
   * @param text text node
   * @param dist distance
   * @return number of added nodes
   */
  private int addText(final byte[] text, final int dist) {
    final int ds = data.meta.size;
    data.text(ds, dist, text, Data.TEXT);
    data.insert(ds);
    return 1;
  }

  /**
   * Adds a processing instruction.
   * @param node node to be added
   * @param pre pre reference
   * @param par parent reference
   * @return number of added nodes
   */
  private int addPI(final ANode node, final int pre, final int par) {
    final int ds = data.meta.size;
    final byte[] v = trim(concat(node.name(), SPACE, node.string()));
    data.text(ds, pre - par, v, Data.PI);
    data.insert(ds);
    return pre + 1;
  }

  /**
   * Adds a comment.
   * @param node node to be added
   * @param pre pre reference
   * @param par parent reference
   * @return number of added nodes
   */
  private int addComm(final ANode node, final int pre, final int par) {
    final int ds = data.meta.size;
    data.text(ds, pre - par, node.string(), Data.COMM);
    data.insert(ds);
    return pre + 1;
  }

  /**
   * Adds an element node.
   * @param node node to be added
   * @param pre pre reference
   * @param par parent reference
   * @return pre value of next node
   */
  private int addElem(final ANode node, final int pre, final int par) {
    final int ds = data.meta.size;

    // add new namespaces
    data.nspaces.prepare();
    final Atts ns = par == -1 ? node.nsScope(null) : node.namespaces();
    final int nl = ns.size();
    for(int n = 0; n < nl; n++) data.nspaces.add(ns.name(n), ns.value(n), ds);

    // analyze node name
    final QNm nm = node.qname();
    final int tn = data.elemNames.index(nm.string(), null, false);
    final int s = size(node, false);
    final int u = data.nspaces.uri(nm.uri());

    // add element node
    data.elem(pre - par, tn, size(node, true), s, u, nl != 0);
    data.insert(ds);

    // add attributes and children
    int p = pre + 1;
    BasicNodeIter iter = node.attributes();
    for(ANode ch; (ch = iter.next()) != null;) p = addAttr(ch, p, pre);
    iter = node.children();
    for(ANode ch; (ch = iter.next()) != null;) p = addNode(ch, p, pre, node);
    data.nspaces.close(ds);

    // update size if additional nodes have been added by the descendants
    if(s != p - pre) data.size(ds, Data.ELEM, p - pre);
    return p;
  }

  /**
   * Determines the number of descendants of a fragment.
   * @param node fragment node
   * @param att count attributes instead of elements
   * @return number of descendants + 1 or attribute size + 1
   */
  private static int size(final ANode node, final boolean att) {
    if(node instanceof DBNode) {
      final DBNode dbn = (DBNode) node;
      final Data data = dbn.data();
      final int kind = node.kind();
      final int pre = dbn.pre();
      return att ? data.attSize(pre, kind) : data.size(pre, kind);
    }

    int s = 1;
    BasicNodeIter iter = node.attributes();
    while(iter.next() != null) ++s;
    if(!att) {
      iter = node.children();
      for(ANode i; (i = iter.next()) != null;) s += size(i, false);
    }
    return s;
  }

  /**
   * Returns a new node without the specified namespace.
   * @param node node to be copied
   * @param ns namespace to be stripped
   * @param ctx database context
   * @return new node
   */
  public static ANode stripNS(final ANode node, final byte[] ns, final Context ctx) {
    if(node.type != NodeType.ELM) return node;

    final MemData md = new MemData(ctx.options);
    final DataBuilder db = new DataBuilder(md);
    db.build(node);

    // flag indicating if namespace should be completely removed
    boolean del = true;
    // loop through all nodes
    for(int pre = 0; pre < md.meta.size; pre++) {
      // only check elements and attributes
      final int kind = md.kind(pre);
      if(kind != Data.ELEM && kind != Data.ATTR) continue;
      // check if namespace is referenced
      final byte[] uri = md.nspaces.uri(md.uri(pre, kind));
      if(uri == null || !eq(uri, ns)) continue;

      final byte[] nm = md.name(pre, kind);
      if(prefix(nm).length == 0) {
        // no prefix: remove namespace from element
        if(kind == Data.ELEM) {
          md.update(pre, Data.ELEM, nm, EMPTY);
          md.nsFlag(pre, false);
        }
      } else {
        // prefix: retain namespace
        del = false;
      }
    }
    if(del) md.nspaces.delete(ns);
    return new DBNode(md);
  }
}
