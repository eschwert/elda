package com.epimorphics.lda.renderers.velocity.tests;

import java.util.Map;

import org.junit.Test;

import com.epimorphics.lda.renderers.velocity.*;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;

public class TestVelocityMetadata {

	@Test public void testMe() {
		// TODO put some actual tests in here!
		Model m = ModelFactory.createDefaultModel();
		ShortNames shortNames = new ShortNames(m);
		@SuppressWarnings("unused") Map<String, Object> meta = Help.getMetadataFrom( shortNames, new IdMap(), m );
	}
}
