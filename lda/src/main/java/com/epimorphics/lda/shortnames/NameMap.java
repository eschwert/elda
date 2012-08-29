/*
    See lda-top/LICENCE (or http://elda.googlecode.com/hg/LICENCE)
    for the licence for this software.
    
    (c) Copyright 2011 Epimorphics Limited
    $Id$
*/
package com.epimorphics.lda.shortnames;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.epimorphics.lda.exceptions.ReusedShortnameException;
import com.epimorphics.lda.support.MultiMap;
import com.epimorphics.lda.vocabularies.ELDA;
import com.epimorphics.lda.vocabularies.OpenSearch;
import com.epimorphics.lda.vocabularies.SPARQL;
import com.epimorphics.lda.vocabularies.XHV;
import com.epimorphics.vocabs.API;
import com.epimorphics.vocabs.NsUtils;
import com.hp.hpl.jena.graph.Node;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.rdf.model.Statement;
import com.hp.hpl.jena.rdf.model.StmtIterator;
import com.hp.hpl.jena.rdf.model.impl.Util;
import com.hp.hpl.jena.shared.PrefixMapping;
import com.hp.hpl.jena.sparql.vocabulary.DOAP;
import com.hp.hpl.jena.vocabulary.DCTerms;
import com.hp.hpl.jena.vocabulary.RDF;
import com.hp.hpl.jena.vocabulary.RDFS;

/**
    Another class maintaining shortnames, this one avoids
    commitments made in Context. It is layered; the first stage is
    what binds shortnames from the LDA config model, and stage2
    handles the model to be rendered. (The difference is that the
    shortnames from the config file are for query, but the ones
    that come later are for rendering.) 
    
    @author chris
*/
public class NameMap {

    static Logger log = LoggerFactory.getLogger(NameMap.class);
    
	/** to make listStatements readable */
	private static final Resource ANY = null;
	
	/** mapping short names to sets of full names */
	private final MultiMap<String, String> mapShortnameToURIs = new MultiMap<String, String>();
	
	/** mapping URIs to some one of their declared shortnames. */
	private final Map<String, String> mapURItoShortName = new HashMap<String, String>();
	
	/** combined prefix mapping from all sources */
	private final PrefixMapping prefixes = PrefixMapping.Factory.create();

	/** 
	 	load a given prefix mapping and model into the map. Looks in the
	 	model for anything with an rdfs:label or api:label property.
	*/
	public void load( PrefixMapping pm, Model m ) {
		prefixes.withDefaultMappings( pm );
		load( m.listStatements( ANY, RDFS.label, ANY ) );
		load( m.listStatements( ANY, API.label, ANY ) );
	}
	
	/**
	    load vocabulary elements from m that are not already
	    defined in this NameMap.
	*/
	public void loadIfNotDefined( PrefixMapping pm, Model m ) {
		NameMap inner = new NameMap();
		inner.load( pm, m );
		inner.done();
	//
		for (String shortName: inner.mapShortnameToURIs.keySet()) {
			if (!mapShortnameToURIs.containsKey(shortName)) {
				mapShortnameToURIs.add(shortName, inner.mapShortnameToURIs.getAll( shortName ) );
			}
		}
	}
	
	/**
	    No more updates to make: check what we've got.
	*/
	public void done() {
		for (String shortName: mapShortnameToURIs.keySet()) {
			Set<String> uris = mapShortnameToURIs.getAll( shortName );
			if (uris.size() > 1) throw new ReusedShortnameException( shortName, uris );
			mapURItoShortName.put( uris.iterator().next(), shortName );
		}
	}

	private void load( StmtIterator si ) {
		while (si.hasNext()) load( si.next() );
	}

	private void load( Statement s ) {
		Resource S = s.getSubject();
		if (S.isURIResource()) {
			String shortName = asString( s.getObject() );
			String uri = S.getURI();
			if (ShortnameUtils.isLegalShortname( shortName )) 
				mapShortnameToURIs.add( shortName, uri );
			else if (s.getPredicate().equals( API.label ))
				log.warn( "ignored bad shortname " + shortName + " for " + s.getModel().shortForm( uri ) ); 
			}
	}

	/**
	    The string version of a node, being its URI if it's a
	    Resource, and its lexical form if it's a Literal.
	*/
	private String asString( RDFNode r ) {
		Node n = r.asNode();
		return n.isLiteral() ? n.getLiteralLexicalForm() : n.getURI();
	}
	
	/**
	    During Stage2, clashing shortnames are resolved rather than permitted.
	*/
	public Stage2NameMap stage2(boolean stripHas) {
		return new Stage2NameMap( stripHas, this );
	}

	/**
	    A Stage2 NameMap adds names to the map, but arranges that if 
	    several full names map to the same localname, then the short
	    forms are prefixed by the declared prefixes of their namespaces.
	    It scans the entire model for properties and datatypes so that
	    the mapping doesn't depend on the (random) order that different
	    full names are encountered in.
	*/
	public static class Stage2NameMap {
		
		/**
		    We need to ensure that the terms used by Elda will always
		    have prefixes available, so we build an automatic prefix
		    mapping which will be used as default.
		*/
		private static PrefixMapping automatic = PrefixMapping.Factory.create()
			.setNsPrefix( "rdf", RDF.getURI() )
			.setNsPrefix( "rdfs", RDFS.getURI() )
			.setNsPrefix( "xhv", XHV.getURI() )
			.setNsPrefix( "dct", DCTerms.getURI() )
			;

		/** The combined namespace prefixes from all models. */
		private PrefixMapping prefixes = PrefixMapping.Factory.create();
		
		/** Terms which arrive only in the model and hence are subordinate
		 	to terms that have been define explicitly.
		 */
		private Set<String> modelTerms = new HashSet<String>();
		
		/** the mapping from full URIs to all their allowed shortnames.*/
		private Map<String, String> uriToName = new HashMap<String, String>();
		
		/** true if we have to convert "hasSpoo" to "spoo". */
		private boolean stripHas;
		
		/** Construct a Stage2 map from a NameMap. */
		public Stage2NameMap( boolean stripHas, NameMap nm ) {
			this.stripHas = stripHas;
			this.prefixes.setNsPrefixes( nm.prefixes );
			this.prefixes.setNsPrefixes( automatic );
			this.uriToName.putAll( nm.mapURItoShortName );
		}

		/** Load a prefix mapping and the terms of a model */
		public Stage2NameMap loadPredicates( PrefixMapping pm, Model m ) {
			prefixes.withDefaultMappings( pm );
			loadPredicatesOf( m );
			return this;
		}

		private void loadPredicatesOf( Model m ) {
			for (StmtIterator sit = m.listStatements(); sit.hasNext();) {
				Statement s = sit.next();
				modelTerms.add( s.getPredicate().getURI() );
				Node o = s.getObject().asNode();
				if (o.isLiteral()) {
					String type = o.getLiteralDatatypeURI();
					if (type != null) modelTerms.add( type );
				}
			}
		}

		/**
		    Answer a map from full URIs to the corresponding short names.
		    If a URI already has a short name, that's what will be used. 
		    URIs that don't yet have one will be given their local name if 
		    it's unambiguous, or their prefixed local name if needed to 
		    disambiguate.
		    
		    TODO: deal with labels with bad syntax.
		*/
		
		public Map<String, String> result() {
			Map<String, String> mapURItoShortName = new HashMap<String, String>();
			mapURItoShortName.putAll( uriToName );
			modelTerms.removeAll( mapURItoShortName.keySet() );
			for (String mt: modelTerms) {
				int cut = Util.splitNamespace( mt );
				String namespace = mt.substring( 0, cut );
				String shortName = mt.substring( cut );
				if (isMagic( namespace )) {
					mapURItoShortName.put( mt, stripHas(shortName) );
				} else {						
					mapURItoShortName.put( mt, Transcoding.encode( prefixes, mt ) );
				}
			}
			return mapURItoShortName;
		}

		private boolean isMagic( String namespace ) {
			if (namespace.equals(XHV.getURI())) return true; 
			// if (true) return false;
			if (namespace.equals(DCTerms.getURI())) return true;
			if (namespace.equals("eh:/")) return true;
			if (namespace.equals(SPARQL.NS)) return true;
			if (namespace.equals(ELDA.COMMON.NS)) return true;
			if (namespace.equals(OpenSearch.getURI())) return true;
			if (namespace.equals(DOAP.NS)) return true;
			if (namespace.equals(API.NS)) return true;
			return false;
		}

		// compatability (with Puelia) code to handle has-stripping
		private String stripHas(String x) {
			if (stripHas && x.startsWith("has") && x.length() > 3) {
				char ch = x.charAt(3);
				if (Character.isUpperCase(ch))
					return Character.toLowerCase(ch) + x.substring(4);
			}
			return x;
		}
	}
}
