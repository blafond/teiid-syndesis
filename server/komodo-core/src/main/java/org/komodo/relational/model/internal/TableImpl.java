/*
 * Copyright Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags and
 * the COPYRIGHT.txt file distributed with this work.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.komodo.relational.model.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.komodo.core.internal.repository.Repository;
import org.komodo.core.repository.Descriptor;
import org.komodo.core.repository.KomodoObject;
import org.komodo.core.repository.ObjectImpl;
import org.komodo.core.repository.Property;
import org.komodo.core.repository.PropertyValueType;
import org.komodo.core.visitor.DdlNodeVisitor;
import org.komodo.core.visitor.DdlNodeVisitor.VisitorExclusions;
import org.komodo.relational.Messages;
import org.komodo.relational.Messages.Relational;
import org.komodo.relational.internal.DataTypeService;
import org.komodo.relational.internal.OptionContainer;
import org.komodo.relational.internal.RelationalModelFactory;
import org.komodo.relational.internal.RelationalObjectImpl;
import org.komodo.relational.internal.TypeResolver;
import org.komodo.relational.model.Column;
import org.komodo.relational.model.ForeignKey;
import org.komodo.relational.model.PrimaryKey;
import org.komodo.relational.model.Table;
import org.komodo.relational.model.UniqueConstraint;
import org.komodo.spi.KException;
import org.komodo.spi.repository.Exportable;
import org.komodo.spi.repository.KomodoType;
import org.komodo.spi.repository.UnitOfWork;
import org.komodo.spi.repository.UnitOfWork.State;
import org.komodo.utils.ArgCheck;
import org.komodo.utils.StringUtils;
import org.teiid.modeshape.sequencer.ddl.StandardDdlLexicon;
import org.teiid.modeshape.sequencer.ddl.TeiidDdlLexicon.Constraint;
import org.teiid.modeshape.sequencer.ddl.TeiidDdlLexicon.CreateTable;
import org.teiid.modeshape.sequencer.ddl.TeiidDdlLexicon.SchemaElement;

/**
 * An implementation of a relational model table.
 */
public class TableImpl extends RelationalObjectImpl implements Table, OptionContainer {
	
    /**
     * An empty array of columns.
     */
    final static ColumnImpl[] NO_COLUMNS = new ColumnImpl[0];
	
    /**
     * An empty collection of unique constraints.
     */
    final static UniqueConstraintImpl[] NO_UNIQUE_CONSTRAINTS = new UniqueConstraintImpl[0];
	
    /**
     * An empty collection of foreign key constraints.
     */
    final static ForeignKeyImpl[] NO_FOREIGN_KEYS = new ForeignKeyImpl[0];

	
    /**
     * The resolver of a {@link Table}.
     */
    public static final TypeResolver< TableImpl > RESOLVER = new TypeResolver< TableImpl >() {

        /**
         * {@inheritDoc}
         *
         * @see org.komodo.relational.internal.TypeResolver#identifier()
         */
        @Override
        public KomodoType identifier() {
            return IDENTIFIER;
        }

        /**
         * {@inheritDoc}
         *
         * @see org.komodo.relational.internal.TypeResolver#owningClass()
         */
        @Override
        public Class< TableImpl > owningClass() {
            return TableImpl.class;
        }

        /**
         * {@inheritDoc}
         *
         * @see org.komodo.relational.internal.TypeResolver#resolvable(org.komodo.core.repository.KomodoObject)
         */
        @Override
        public boolean resolvable( final KomodoObject kobject ) throws KException {
            return ObjectImpl.validateType( kobject, CreateTable.TABLE_STATEMENT );
        }

        /**
         * {@inheritDoc}
         *
         * @see org.komodo.relational.internal.TypeResolver#resolve(org.komodo.core.repository.KomodoObject)
         */
        @Override
        public TableImpl resolve( final KomodoObject kobject ) throws KException {
            if ( kobject.getTypeId() == Table.TYPE_ID ) {
                return ( TableImpl )kobject;
            }

            return new TableImpl( kobject.getTransaction(), kobject.getRepository(), kobject.getAbsolutePath() );
        }

    };

    /**
     * The allowed child types.
     */
    private static final KomodoType[] CHILD_TYPES = new KomodoType[] { Column.IDENTIFIER,
                                                                      ForeignKey.IDENTIFIER, 
                                                                      PrimaryKey.IDENTIFIER, UniqueConstraint.IDENTIFIER };

    private enum StandardOption {

        ANNOTATION( null ),
        CARDINALITY( Long.toString( Table.DEFAULT_CARDINALITY ) ),
        MATERIALIZED( Boolean.toString( Table.DEFAULT_MATERIALIZED ) ),
        MATERIALIZED_TABLE( null ),
        NAMEINSOURCE( null ),
        UPDATABLE( Boolean.toString( Table.DEFAULT_UPDATABLE ) ),
        UUID( null );

        private static Map< String, String > _defaultValues = null;

        /**
         * @return an unmodifiable collection of the names and default values of all the standard options (never <code>null</code>
         *         or empty)
         */
        static Map< String, String > defaultValues() {
            if ( _defaultValues == null ) {
                final StandardOption[] options = values();
                final Map< String, String > temp = new HashMap< >();

                for ( final StandardOption option : options ) {
                    temp.put( option.name(), option.defaultValue );
                }

                _defaultValues = Collections.unmodifiableMap( temp );
            }

            return _defaultValues;
        }

        /**
         * @param name
         *        the name being checked (can be <code>null</code>)
         * @return <code>true</code> if the name is the name of a standard option
         */
        static boolean isValid( final String name ) {
            for ( final StandardOption option : values() ) {
                if ( option.name().equals( name ) ) {
                    return true;
                }
            }

            return false;
        }

        private final String defaultValue;

        private StandardOption( final String defaultValue ) {
            this.defaultValue = defaultValue;
        }

    }

    /**
     * @param uow
     *        the transaction (cannot be <code>null</code> or have a state that is not {@link State#NOT_STARTED})
     * @param repository
     *        the repository where the relational object exists (cannot be <code>null</code>)
     * @param workspacePath
     *        the workspace relative path (cannot be empty)
     * @throws KException
     *         if an error occurs or if node at specified path is not a table
     */
    public TableImpl( final UnitOfWork uow,
                      final Repository repository,
                      final String workspacePath ) throws KException {
        super(uow, repository, workspacePath);
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.model.Table#addColumn(org.komodo.spi.repository.Repository.UnitOfWork, java.lang.String)
     */
    @Override
    public ColumnImpl addColumn(
                             final String columnName ) throws KException {
        return RelationalModelFactory.createColumn( getTransaction(), getRepository(), this, columnName );
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.model.Table#addForeignKey(org.komodo.spi.repository.Repository.UnitOfWork, java.lang.String,
     *      org.komodo.relational.model.Table)
     */
    @Override
    public ForeignKeyImpl addForeignKey(
                                     final String foreignKeyName,
                                     final Table referencedTable ) throws KException {
        return RelationalModelFactory.createForeignKey( getTransaction(), getRepository(), this, foreignKeyName, referencedTable );
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.model.Table#addUniqueConstraint(org.komodo.spi.repository.Repository.UnitOfWork,
     *      java.lang.String)
     */
    @Override
    public UniqueConstraintImpl addUniqueConstraint(
                                                 final String constraintName ) throws KException {
        return RelationalModelFactory.createUniqueConstraint( getTransaction(), getRepository(), this, constraintName );
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.model.Table#getCardinality(org.komodo.spi.repository.Repository.UnitOfWork)
     */
    @Override
    public long getCardinality() throws KException {
        final String option = OptionContainerUtils.getOption( getTransaction(), this, StandardOption.CARDINALITY.name() );

        if ( option == null ) {
            return Table.DEFAULT_CARDINALITY;
        }

        return Long.parseLong( option );
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.internal.RelationalObjectImpl#getChildren(java.lang.String[])
     */
    @Override
    public KomodoObject[] getChildren( final String... namePatterns ) throws KException {
        ArgCheck.isNotNull( getTransaction(), "transaction" ); //$NON-NLS-1$
        ArgCheck.isTrue( ( getTransaction().getState() == State.NOT_STARTED ), "transaction state is not NOT_STARTED" ); //$NON-NLS-1$

        // constraints are access patterns, primary key, and unique constraints
        final KomodoObject[] constraints = getChildrenOfType( Constraint.TABLE_ELEMENT, namePatterns );
        final Column[] columns = getColumns( namePatterns );
        final ForeignKey[] foreignKeys = getForeignKeys( namePatterns );

        final int size = constraints.length + columns.length + foreignKeys.length;
        final KomodoObject[] result = new KomodoObject[ size ];
        System.arraycopy( constraints, 0, result, 0, constraints.length );
        System.arraycopy( columns, 0, result, constraints.length, columns.length );
        System.arraycopy( foreignKeys, 0, result, constraints.length + columns.length, foreignKeys.length );

        return result;
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.core.repository.ObjectImpl#getChildTypes()
     */
    @Override
    public KomodoType[] getChildTypes() {
        return CHILD_TYPES;
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.model.Table#getColumns(org.komodo.spi.repository.Repository.UnitOfWork, java.lang.String[])
     */
    @Override
    public ColumnImpl[] getColumns(
                                final String... namePatterns ) throws KException {
        ArgCheck.isNotNull( getTransaction(), "transaction" ); //$NON-NLS-1$
        ArgCheck.isTrue( ( getTransaction().getState() == State.NOT_STARTED ), "transaction state is not NOT_STARTED" ); //$NON-NLS-1$

        final List< ColumnImpl > result = new ArrayList< ColumnImpl >();

        for ( final KomodoObject kobject : getChildrenOfType( CreateTable.TABLE_ELEMENT, namePatterns ) ) {
            final ColumnImpl column = new ColumnImpl( getTransaction(), getRepository(), kobject.getAbsolutePath() );
            result.add( column );
        }

        if ( result.isEmpty() ) {
            return NO_COLUMNS;
        }

        return result.toArray( new ColumnImpl[ result.size() ] );
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.internal.OptionContainer#getCustomOptions()
     */
    @Override
    public StatementOption[] getCustomOptions() throws KException {
        return OptionContainerUtils.getCustomOptions( getTransaction(), this );
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.model.Table#getDescription()
     */
    @Override
    public String getDescription() throws KException {
        return OptionContainerUtils.getOption(getTransaction(), this, StandardOption.ANNOTATION.name());
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.model.Table#getForeignKeys(org.komodo.spi.repository.Repository.UnitOfWork, java.lang.String[])
     */
    @Override
    public ForeignKeyImpl[] getForeignKeys(
                                        final String... namePatterns ) throws KException {
        ArgCheck.isNotNull( getTransaction(), "transaction" ); //$NON-NLS-1$
        ArgCheck.isTrue( ( getTransaction().getState() == State.NOT_STARTED ), "transaction state is not NOT_STARTED" ); //$NON-NLS-1$

        final List< ForeignKeyImpl > result = new ArrayList< ForeignKeyImpl >();

        for ( final KomodoObject kobject : getChildrenOfType( Constraint.FOREIGN_KEY_CONSTRAINT, namePatterns ) ) {
            final ForeignKeyImpl constraint = new ForeignKeyImpl( getTransaction(), getRepository(), kobject.getAbsolutePath() );
            result.add( constraint );
        }

        if ( result.isEmpty() ) {
            return NO_FOREIGN_KEYS;
        }

        return result.toArray( new ForeignKeyImpl[ result.size() ] );
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.model.Table#getMaterializedTable(org.komodo.spi.repository.Repository.UnitOfWork)
     */
    @Override
    public String getMaterializedTable() throws KException {
        return OptionContainerUtils.getOption(getTransaction(), this, StandardOption.MATERIALIZED_TABLE.name());
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.model.Table#getNameInSource(org.komodo.spi.repository.Repository.UnitOfWork)
     */
    @Override
    public String getNameInSource() throws KException {
        return OptionContainerUtils.getOption(getTransaction(), this, StandardOption.NAMEINSOURCE.name());
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.model.Table#getOnCommitValue(org.komodo.spi.repository.Repository.UnitOfWork)
     */
    @Override
    public OnCommit getOnCommitValue() throws KException {
        final String value = getObjectProperty(getTransaction(), PropertyValueType.STRING, "getOnCommitValue", //$NON-NLS-1$
                                               StandardDdlLexicon.ON_COMMIT_VALUE);

        if (StringUtils.isBlank(value)) {
            return null;
        }

        return OnCommit.fromValue(value);
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.model.Table#getPrimaryKey(org.komodo.spi.repository.Repository.UnitOfWork)
     */
    @Override
    public PrimaryKeyImpl getPrimaryKey() throws KException {
    	PrimaryKeyImpl result = null;

        for ( final KomodoObject kobject : getChildrenOfType( Constraint.TABLE_ELEMENT ) ) {
            final Property prop = kobject.getRawProperty( getTransaction(), Constraint.TYPE );

            if ( PrimaryKey.CONSTRAINT_TYPE.toValue().equals( prop.getStringValue( getTransaction() ) ) ) {
                result = new PrimaryKeyImpl( getTransaction(), getRepository(), kobject.getAbsolutePath() );
                break;
            }
        }

        return result;
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.core.repository.ObjectImpl#getPrimaryType()
     */
    @Override
    public Descriptor getPrimaryType( ) throws KException {
        return OptionContainerUtils.createPrimaryType(getTransaction(), this, super.getPrimaryType( ));
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.internal.RelationalObjectImpl#getProperty(java.lang.String)
     */
    @Override
    public Property getProperty(
                                 final String name ) throws KException {
        return OptionContainerUtils.getProperty( getTransaction(), this, name, super.getProperty( name ) );
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.internal.RelationalObjectImpl#getPropertyNames()
     */
    @Override
    public String[] getPropertyNames() throws KException {
        return OptionContainerUtils.getPropertyNames( getTransaction(), this, super.getPropertyNames( ) );
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.model.Table#getQueryExpression(org.komodo.spi.repository.Repository.UnitOfWork)
     */
    @Override
    public String getQueryExpression() throws KException {
        return getObjectProperty(getTransaction(), PropertyValueType.STRING, "getQueryExpression", CreateTable.QUERY_EXPRESSION); //$NON-NLS-1$
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.model.SchemaElement#getSchemaElementType(org.komodo.spi.repository.Repository.UnitOfWork)
     */
    @Override
    public SchemaElementType getSchemaElementType() throws KException {
        final String value = getObjectProperty(getTransaction(), PropertyValueType.STRING, "getSchemaElementType", //$NON-NLS-1$
                                               SchemaElement.TYPE);

        if (StringUtils.isBlank(value)) {
            return null;
        }

        return SchemaElementType.fromValue(value);
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.internal.OptionContainer#getStandardOptions()
     */
    @Override
    public Map< String, String > getStandardOptions() {
        return StandardOption.defaultValues();
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.internal.OptionContainer#getStatementOptionNames()
     */
    @Override
    public String[] getStatementOptionNames() throws KException {
        return OptionContainerUtils.getOptionNames( getTransaction(), this );
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.internal.OptionContainer#getStatementOptions()
     */
    @Override
    public StatementOption[] getStatementOptions() throws KException {
        return OptionContainerUtils.getOptions( getTransaction(), this );
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.model.Table#getTemporaryTableType(org.komodo.spi.repository.Repository.UnitOfWork)
     */
    @Override
    public TemporaryType getTemporaryTableType() throws KException {
        final String value = getObjectProperty(getTransaction(), PropertyValueType.STRING, "getTemporaryTableType", //$NON-NLS-1$
                                               StandardDdlLexicon.TEMPORARY);

        if (StringUtils.isBlank(value)) {
            return null;
        }

        return TemporaryType.fromValue(value);
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.core.repository.KomodoObject#getTypeId()
     */
    @Override
    public int getTypeId() {
        return TYPE_ID;
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.core.repository.ObjectImpl#getTypeIdentifier()
     */
    @Override
    public KomodoType getTypeIdentifier() {
        return Table.IDENTIFIER;
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.model.Table#getUniqueConstraints(org.komodo.spi.repository.Repository.UnitOfWork,
     *      java.lang.String[])
     */
    @Override
    public UniqueConstraintImpl[] getUniqueConstraints(
                                                    final String... namePatterns ) throws KException {
        ArgCheck.isNotNull( getTransaction(), "transaction" ); //$NON-NLS-1$
        ArgCheck.isTrue( ( getTransaction().getState() == State.NOT_STARTED ), "transaction state is not NOT_STARTED" ); //$NON-NLS-1$

        final List< UniqueConstraintImpl > result = new ArrayList< UniqueConstraintImpl >();

        for ( final KomodoObject kobject : getChildrenOfType( Constraint.TABLE_ELEMENT, namePatterns ) ) {
            final Property prop = kobject.getRawProperty( getTransaction(), Constraint.TYPE );

            if ( UniqueConstraint.CONSTRAINT_TYPE.toValue().equals( prop.getStringValue( getTransaction() ) ) ) {
                final UniqueConstraintImpl constraint = new UniqueConstraintImpl( getTransaction(),
                                                                              getRepository(),
                                                                              kobject.getAbsolutePath() );
                result.add( constraint );
            }
        }

        if ( result.isEmpty() ) {
            return NO_UNIQUE_CONSTRAINTS;
        }

        return result.toArray( new UniqueConstraintImpl[ result.size() ] );
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.model.Table#getUuid(org.komodo.spi.repository.Repository.UnitOfWork)
     */
    @Override
    public String getUuid() throws KException {
        return OptionContainerUtils.getOption( getTransaction(), this, StandardOption.UUID.name() );
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.internal.RelationalObjectImpl#getParent()
     */
    @Override
    public ModelImpl getParent() throws KException {
        ArgCheck.isNotNull( getTransaction(), "transaction" ); //$NON-NLS-1$
        ArgCheck.isTrue( ( getTransaction().getState() == State.NOT_STARTED ), "transaction state must be NOT_STARTED" ); //$NON-NLS-1$

        final KomodoObject parent = super.getParent( );
        final ModelImpl result = ModelImpl.RESOLVER.resolve( parent );
        return result;
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.core.repository.ObjectImpl#hasProperties()
     */
    @Override
    public boolean hasProperties() throws KException {
        return OptionContainerUtils.hasProperties( getTransaction(), this, super.hasProperties( ) );
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.internal.RelationalObjectImpl#hasProperty(java.lang.String)
     */
    @Override
    public boolean hasProperty(
                                final String name ) throws KException {
        return OptionContainerUtils.hasProperty( getTransaction(), this, name, super.hasProperty( name ) );
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.internal.OptionContainer#isCustomOption(org.komodo.spi.repository.Repository.UnitOfWork,
     *      java.lang.String)
     */
    @Override
    public boolean isCustomOption(
                                   final String name ) throws KException {
        return OptionContainerUtils.hasCustomOption( getTransaction(), this, name );
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.model.Table#isMaterialized(org.komodo.spi.repository.Repository.UnitOfWork)
     */
    @Override
    public boolean isMaterialized() throws KException {
        final String option = OptionContainerUtils.getOption( getTransaction(), this, StandardOption.MATERIALIZED.name() );

        if ( option == null ) {
            return Table.DEFAULT_MATERIALIZED;
        }

        return Boolean.parseBoolean( option );
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.internal.OptionContainer#isStandardOption(java.lang.String)
     */
    @Override
    public boolean isStandardOption( final String name ) {
        return StandardOption.isValid( name );
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.model.Table#isUpdatable(org.komodo.spi.repository.Repository.UnitOfWork)
     */
    @Override
    public boolean isUpdatable() throws KException {
        final String option = OptionContainerUtils.getOption( getTransaction(), this, StandardOption.UPDATABLE.name() );

        if ( option == null ) {
            return Table.DEFAULT_UPDATABLE;
        }

        return Boolean.parseBoolean( option );
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.model.Table#removeColumn(org.komodo.spi.repository.Repository.UnitOfWork, java.lang.String)
     */
    @Override
    public void removeColumn(
                              final String columnToRemove ) throws KException {
        ArgCheck.isNotNull( getTransaction(), "transaction" ); //$NON-NLS-1$
        ArgCheck.isTrue( ( getTransaction().getState() == State.NOT_STARTED ), "transaction state is not NOT_STARTED" ); //$NON-NLS-1$
        ArgCheck.isNotEmpty( columnToRemove, "columnToRemove" ); //$NON-NLS-1$

        final ColumnImpl[] columns = getColumns( columnToRemove );

        if ( columns.length == 0 ) {
            throw new KException( Messages.getString( Relational.COLUMN_NOT_FOUND_TO_REMOVE, columnToRemove ) );
        }

        // remove first occurrence
        columns[ 0 ].remove( getTransaction() );
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.model.Table#removeForeignKey(org.komodo.spi.repository.Repository.UnitOfWork, java.lang.String)
     */
    @Override
    public void removeForeignKey(
                                  final String foreignKeyToRemove ) throws KException {
        ArgCheck.isNotNull( getTransaction(), "transaction" ); //$NON-NLS-1$
        ArgCheck.isTrue( ( getTransaction().getState() == State.NOT_STARTED ), "transaction state is not NOT_STARTED" ); //$NON-NLS-1$
        ArgCheck.isNotEmpty( foreignKeyToRemove, "foreignKeyToRemove" ); //$NON-NLS-1$

        final ForeignKeyImpl[] foreignKeys = getForeignKeys( foreignKeyToRemove );

        if ( foreignKeys.length == 0 ) {
            throw new KException( Messages.getString( Relational.CONSTRAINT_NOT_FOUND_TO_REMOVE,
                                                      foreignKeyToRemove,
                                                      ForeignKey.CONSTRAINT_TYPE.toString() ) );
        }

        // remove first occurrence
        foreignKeys[ 0 ].remove( getTransaction() );
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.model.Table#removePrimaryKey(org.komodo.spi.repository.Repository.UnitOfWork)
     */
    @Override
    public void removePrimaryKey() throws KException {
        ArgCheck.isNotNull( getTransaction(), "transaction" ); //$NON-NLS-1$
        ArgCheck.isTrue( ( getTransaction().getState() == State.NOT_STARTED ), "transaction state is not NOT_STARTED" ); //$NON-NLS-1$

        final PrimaryKeyImpl primaryKey = getPrimaryKey( );

        if ( primaryKey == null ) {
            throw new KException( Messages.getString( Relational.CONSTRAINT_NOT_FOUND_TO_REMOVE,
                                                      PrimaryKey.CONSTRAINT_TYPE.toString(),
                                                      PrimaryKey.CONSTRAINT_TYPE.toString() ) );
        }

        primaryKey.remove( getTransaction() );
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.internal.OptionContainer#removeStatementOption(org.komodo.spi.repository.Repository.UnitOfWork,
     *      java.lang.String)
     */
    @Override
    public void removeStatementOption(
                                       final String optionToRemove ) throws KException {
        OptionContainerUtils.removeOption( getTransaction(), this, optionToRemove );
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.model.Table#removeUniqueConstraint(org.komodo.spi.repository.Repository.UnitOfWork,
     *      java.lang.String)
     */
    @Override
    public void removeUniqueConstraint(
                                        final String constraintToRemove ) throws KException {
        ArgCheck.isNotNull( getTransaction(), "transaction" ); //$NON-NLS-1$
        ArgCheck.isTrue( ( getTransaction().getState() == State.NOT_STARTED ), "transaction state is not NOT_STARTED" ); //$NON-NLS-1$
        ArgCheck.isNotEmpty( constraintToRemove, "constraintToRemove" ); //$NON-NLS-1$

        final UniqueConstraintImpl[] uniqueConstraints = getUniqueConstraints( constraintToRemove );

        if ( uniqueConstraints.length == 0 ) {
            throw new KException( Messages.getString( Relational.CONSTRAINT_NOT_FOUND_TO_REMOVE,
                                                      constraintToRemove,
                                                      UniqueConstraint.CONSTRAINT_TYPE.toString() ) );
        }

        // remove first occurrence
        uniqueConstraints[ 0 ].remove( getTransaction() );
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.model.Table#setCardinality(org.komodo.spi.repository.Repository.UnitOfWork, long)
     */
    @Override
    public void setCardinality(
                                final long newCardinality ) throws KException {
        setStatementOption(StandardOption.CARDINALITY.name(), Long.toString(newCardinality));
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.model.Table#setDescription(java.lang.String)
     */
    @Override
    public void setDescription(
                                final String newDescription ) throws KException {
        setStatementOption(StandardOption.ANNOTATION.name(), newDescription);
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.model.Table#setMaterialized(org.komodo.spi.repository.Repository.UnitOfWork, boolean)
     */
    @Override
    public void setMaterialized(
                                 final boolean newMaterialized ) throws KException {
        setStatementOption(StandardOption.MATERIALIZED.name(), Boolean.toString(newMaterialized));
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.model.Table#setMaterializedTable(org.komodo.spi.repository.Repository.UnitOfWork,
     *      java.lang.String)
     */
    @Override
    public void setMaterializedTable(
                                      final String newMaterializedTable ) throws KException {
        setStatementOption(StandardOption.MATERIALIZED_TABLE.name(), newMaterializedTable);
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.model.Table#setNameInSource(org.komodo.spi.repository.Repository.UnitOfWork, java.lang.String)
     */
    @Override
    public void setNameInSource(
                                 final String newNameInSource ) throws KException {
        setStatementOption(StandardOption.NAMEINSOURCE.name(), newNameInSource);
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.model.Table#setOnCommitValue(org.komodo.spi.repository.Repository.UnitOfWork,
     *      org.komodo.relational.model.Table.OnCommit)
     */
    @Override
    public void setOnCommitValue(
                                  final OnCommit newOnCommit ) throws KException {
        final String newValue = (newOnCommit == null) ? null : newOnCommit.toValue();
        setObjectProperty(getTransaction(), "setOnCommitValue", StandardDdlLexicon.ON_COMMIT_VALUE, newValue); //$NON-NLS-1$
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.model.Table#setPrimaryKey(org.komodo.spi.repository.Repository.UnitOfWork, java.lang.String)
     */
    @Override
    public PrimaryKeyImpl setPrimaryKey(
                                     final String newPrimaryKeyName ) throws KException {
        // delete existing primary key (don't call removePrimaryKey as it throws exception if one does not exist)
        final PrimaryKeyImpl primaryKey = getPrimaryKey( );

        if ( primaryKey != null ) {
            primaryKey.remove( getTransaction() );
        }

        final PrimaryKeyImpl result = RelationalModelFactory.createPrimaryKey( getTransaction(), getRepository(), this, newPrimaryKeyName );
        return result;
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.core.repository.ObjectImpl#setProperty(java.lang.String, java.lang.Object[])
     */
    @Override
    public void setProperty(
                             final String propertyName,
                             final Object... values ) throws KException {
        // if an option was not set then set a property
        if ( !OptionContainerUtils.setProperty( getTransaction(), this, propertyName, values ) ) {
            super.setProperty( propertyName, values );
        }
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.model.Table#setQueryExpression(org.komodo.spi.repository.Repository.UnitOfWork,
     *      java.lang.String)
     */
    @Override
    public void setQueryExpression(
                                    final String newQueryExpression ) throws KException {
        setObjectProperty(getTransaction(), "setQueryExpression", CreateTable.QUERY_EXPRESSION, newQueryExpression); //$NON-NLS-1$
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.model.SchemaElement#setSchemaElementType(org.komodo.spi.repository.Repository.UnitOfWork,
     *      org.komodo.relational.model.SchemaElement.SchemaElementType)
     */
    @Override
    public void setSchemaElementType(
                                      final SchemaElementType newSchemaElementType ) throws KException {
        final String newValue = ((newSchemaElementType == null) ? SchemaElementType.DEFAULT_VALUE.name() : newSchemaElementType.name());
        setObjectProperty(getTransaction(), "setSchemaElementType", SchemaElement.TYPE, newValue); //$NON-NLS-1$
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.internal.OptionContainer#setStatementOption(java.lang.String,
     *      java.lang.String)
     */
    @Override
    public StatementOption setStatementOption(
                                               final String optionName,
                                               final String optionValue ) throws KException {
        return OptionContainerUtils.setOption( getTransaction(), this, optionName, optionValue );
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.model.Table#setTemporaryTableType(org.komodo.spi.repository.Repository.UnitOfWork,
     *      org.komodo.relational.model.Table.TemporaryType)
     */
    @Override
    public void setTemporaryTableType(
                                       final TemporaryType newTempType ) throws KException {
        final String newValue = ((newTempType == null) ? null : newTempType.name());
        setObjectProperty(getTransaction(), "setTemporaryTableType", StandardDdlLexicon.TEMPORARY, newValue); //$NON-NLS-1$
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.model.Table#setUpdatable(org.komodo.spi.repository.Repository.UnitOfWork, boolean)
     */
    @Override
    public void setUpdatable(
                              final boolean newUpdatable ) throws KException {
        setStatementOption(StandardOption.UPDATABLE.name(), Boolean.toString(newUpdatable));
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.relational.model.Table#setUuid(org.komodo.spi.repository.Repository.UnitOfWork, java.lang.String)
     */
    @Override
    public void setUuid(
                         final String newUuid ) throws KException {
        setStatementOption( StandardOption.UUID.name(), newUuid );
    }

    private String exportDdl(UnitOfWork transaction, Properties exportProperties) throws Exception {
        List<VisitorExclusions> exclusions = new ArrayList<VisitorExclusions>();
        if( exportProperties != null && !exportProperties.isEmpty() ) {
            if(exportProperties.containsKey(Exportable.EXCLUDE_TABLE_CONSTRAINTS_KEY)) {
                exclusions.add(VisitorExclusions.EXCLUDE_TABLE_CONSTRAINTS);
            }
        }
        DdlNodeVisitor visitor = new DdlNodeVisitor(new DataTypeService(), false, exclusions.toArray(new VisitorExclusions[0]));
        visitor.visit(transaction, this);

        String result = visitor.getDdl();
        return result;
    }

    /**
     * {@inheritDoc}
     *
     * @see org.komodo.spi.repository.Exportable#export(org.komodo.spi.repository.Repository.UnitOfWork, java.util.Properties)
     */
    @Override
    public byte[] export(Properties exportProperties) throws KException {
        ArgCheck.isNotNull(getTransaction());

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("tableimpl-export: transaction = {0}", getTransaction().getName()); //$NON-NLS-1$
        }

        try {
            String result = exportDdl(getTransaction(), exportProperties);

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("TableImpl: transaction = {0}, xml = {1}", //$NON-NLS-1$
                             getTransaction().getName(),
                             result);
            }

            return result.getBytes();

        } catch (final Exception e) {
            throw handleError(e);
        }
    }
    
    @Override
    public ModelImpl getRelationalParent() throws KException {
    	return this.getParent();
    }

}
