/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.query.hql.internal;

import java.util.BitSet;

import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.InputMismatchException;
import org.antlr.v4.runtime.NoViableAltException;
import org.hibernate.QueryException;
import org.hibernate.grammars.hql.HqlLexer;
import org.hibernate.grammars.hql.HqlParser;
import org.hibernate.query.SemanticException;
import org.hibernate.query.hql.HqlLogging;
import org.hibernate.query.hql.HqlTranslator;
import org.hibernate.query.hql.spi.SqmCreationOptions;
import org.hibernate.query.sqm.InterpretationException;
import org.hibernate.query.sqm.ParsingException;
import org.hibernate.query.sqm.internal.SqmTreePrinter;
import org.hibernate.query.sqm.spi.SqmCreationContext;
import org.hibernate.query.sqm.tree.SqmStatement;

import org.antlr.v4.runtime.ANTLRErrorListener;
import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.DefaultErrorStrategy;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.atn.ATNConfigSet;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.misc.ParseCancellationException;

import static java.util.stream.Collectors.toList;

/**
 * Standard implementation of {@link HqlTranslator}.
 *
 * @author Steve Ebersole
 */
public class StandardHqlTranslator implements HqlTranslator {

	private final SqmCreationContext sqmCreationContext;
	private final SqmCreationOptions sqmCreationOptions;


	public StandardHqlTranslator(
			SqmCreationContext sqmCreationContext,
			SqmCreationOptions sqmCreationOptions) {
		this.sqmCreationContext = sqmCreationContext;
		this.sqmCreationOptions = sqmCreationOptions;
	}

	@Override
	public <R> SqmStatement<R> translate(String query, Class<R> expectedResultType) {
		HqlLogging.QUERY_LOGGER.debugf( "HQL : " + query );

		final HqlParser.StatementContext hqlParseTree = parseHql( query );

		// then we perform semantic analysis and build the semantic representation...
		try {
			final SqmStatement<R> sqmStatement = SemanticQueryBuilder.buildSemanticModel(
					hqlParseTree,
					expectedResultType,
					sqmCreationOptions,
					sqmCreationContext
			);

			// Log the SQM tree (if enabled)
			SqmTreePrinter.logTree( sqmStatement );

			return sqmStatement;
		}
		catch (QueryException e) {
			throw e;
		}
		catch (Exception e) {
			throw new InterpretationException( query, e );
		}
	}

	private HqlParser.StatementContext parseHql(String hql) {
		// Build the lexer
		final HqlLexer hqlLexer = HqlParseTreeBuilder.INSTANCE.buildHqlLexer( hql );

		// Build the parse tree
		final HqlParser hqlParser = HqlParseTreeBuilder.INSTANCE.buildHqlParser( hql, hqlLexer );

		ANTLRErrorListener errorListener = new ANTLRErrorListener() {
			@Override
			public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine, String msg, RecognitionException e) {
				String errorText = "At " + line + ":" + charPositionInLine;
				if ( offendingSymbol instanceof CommonToken ) {
					String token = ( (CommonToken) offendingSymbol ).getText();
					if ( token != null && !token.isEmpty() ) {
						errorText += " and token '" + token + "'";
					}
				}
				errorText += ", ";
				if ( e instanceof NoViableAltException ) {
					errorText +=  msg.substring( 0, msg.indexOf("'") );
					String lineText = hql.lines().collect( toList() ).get( line-1 );
					String text = lineText.substring( 0, charPositionInLine ) + "*" + lineText.substring( charPositionInLine );
					errorText += "'" + text + "'";
				}
				else if ( e instanceof InputMismatchException ) {
					errorText += msg.substring( 0,msg.length()-1 ).replace(" expecting {", ", expecting one of the following tokens: ");
				}
				else  {
					errorText += msg;
				}
				throw new ParsingException( errorText );
			}

			@Override
			public void reportAmbiguity(Parser recognizer, DFA dfa, int startIndex, int stopIndex, boolean exact, BitSet ambigAlts, ATNConfigSet configs) {
			}

			@Override
			public void reportAttemptingFullContext(Parser recognizer, DFA dfa, int startIndex, int stopIndex, BitSet conflictingAlts, ATNConfigSet configs) {
			}

			@Override
			public void reportContextSensitivity(Parser recognizer, DFA dfa, int startIndex, int stopIndex, int prediction, ATNConfigSet configs) {
			}
		};

		// try to use SLL(k)-based parsing first - its faster
		hqlLexer.addErrorListener( errorListener );
		hqlParser.getInterpreter().setPredictionMode( PredictionMode.SLL );
		hqlParser.removeErrorListeners();
		hqlParser.addErrorListener( errorListener );
		hqlParser.setErrorHandler( new BailErrorStrategy() );

		try {
			return hqlParser.statement();
		}
		catch ( ParseCancellationException e) {
			// reset the input token stream and parser state
			hqlLexer.reset();
			hqlParser.reset();

			// fall back to LL(k)-based parsing
			hqlParser.getInterpreter().setPredictionMode( PredictionMode.LL );
			hqlParser.setErrorHandler( new DefaultErrorStrategy() );

			return hqlParser.statement();
		}
		catch ( ParsingException ex ) {
			throw new SemanticException( "Illegal HQL syntax [" + ex.getMessage() + "]", hql, ex );
		}
	}
}
