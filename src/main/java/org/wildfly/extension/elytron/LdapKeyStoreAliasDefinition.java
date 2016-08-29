/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2016 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wildfly.extension.elytron;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.wildfly.extension.elytron.CertificateChainAttributeDefinitions.CERTIFICATE;
import static org.wildfly.extension.elytron.CertificateChainAttributeDefinitions.getNamedCertificateList;
import static org.wildfly.extension.elytron.CertificateChainAttributeDefinitions.writeCertificate;
import static org.wildfly.extension.elytron.CertificateChainAttributeDefinitions.writeCertificates;
import static org.wildfly.extension.elytron.ElytronExtension.ISO_8601_FORMAT;
import static org.wildfly.extension.elytron._private.ElytronSubsystemMessages.ROOT_LOGGER;

import java.security.KeyStore;
import java.security.KeyStore.PrivateKeyEntry;
import java.security.KeyStore.SecretKeyEntry;
import java.security.KeyStore.TrustedCertificateEntry;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.ResourceDefinition;
import org.jboss.as.controller.SimpleAttributeDefinition;
import org.jboss.as.controller.SimpleAttributeDefinitionBuilder;
import org.jboss.as.controller.SimpleResourceDefinition;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.as.controller.registry.OperationEntry;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;
import org.wildfly.extension.elytron.LdapKeyStoreDefinition.LdapKeyStoreRuntimeOnlyHandler;
import org.wildfly.security.keystore.LdapKeyStore;
import org.wildfly.security.keystore.PasswordEntry;

/**
 * A {@link ResourceDefinition} for an alias stored within a {@link LdapKeyStore}.
 *
 * @author <a href="mailto:jkalina@redhat.com">Jan Kalina</a>
 */
class LdapKeyStoreAliasDefinition extends SimpleResourceDefinition {

    static final SimpleAttributeDefinition CREATION_DATE = new SimpleAttributeDefinitionBuilder(ElytronDescriptionConstants.CREATION_DATE, ModelType.STRING)
            .setStorageRuntime()
            .build();

    static final SimpleAttributeDefinition ENTRY_TYPE = new SimpleAttributeDefinitionBuilder(ElytronDescriptionConstants.ENTRY_TYPE, ModelType.STRING)
            .setStorageRuntime()
            .setAllowedValues(PasswordEntry.class.getSimpleName(), PrivateKeyEntry.class.getSimpleName(),
                    SecretKeyEntry.class.getSimpleName(), TrustedCertificateEntry.class.getSimpleName(), "Other")
            .build();

    LdapKeyStoreAliasDefinition() {
        super(new Parameters(PathElement.pathElement(ElytronDescriptionConstants.ALIAS), ElytronExtension.getResourceDescriptionResolver(ElytronDescriptionConstants.LDAP_KEY_STORE, ElytronDescriptionConstants.ALIAS))
                .setRemoveHandler(new RemoveHandler())
                .setAddRestartLevel(OperationEntry.Flag.RESTART_NONE)
                .setRemoveRestartLevel(OperationEntry.Flag.RESTART_RESOURCE_SERVICES)
                .setRuntime());
    }

    @Override
    public void registerAttributes(ManagementResourceRegistration resourceRegistration) {
        resourceRegistration.registerReadOnlyAttribute(CREATION_DATE, new LdapKeyStoreRuntimeOnlyHandler(false) {

            @Override
            protected void performRuntime(ModelNode result, ModelNode operation, LdapKeyStoreService keyStoreService) throws OperationFailedException {
                SimpleDateFormat sdf = new SimpleDateFormat(ISO_8601_FORMAT);

                String alias = alias(operation);

                Date creationDate;
                try {
                    creationDate = keyStoreService.getValue().getCreationDate(alias);
                } catch (KeyStoreException | RuntimeException e) {
                    ROOT_LOGGER.tracef(e, "Unable to populate %s", CREATION_DATE);
                    return;
                }

                if (creationDate != null) {
                    result.set(sdf.format(creationDate));
                }
            }
        });

        resourceRegistration.registerReadOnlyAttribute(ENTRY_TYPE, new LdapKeyStoreRuntimeOnlyHandler(false) {

            @Override
            protected void performRuntime(ModelNode result, ModelNode operation, LdapKeyStoreService keyStoreService)
                    throws OperationFailedException {
                KeyStore keyStore = keyStoreService.getValue();
                String alias = alias(operation);
                try {
                    if (keyStore.entryInstanceOf(alias, PrivateKeyEntry.class)) {
                        result.set(PrivateKeyEntry.class.getSimpleName());
                    } else if (keyStore.entryInstanceOf(alias, SecretKeyEntry.class)) {
                        result.set(SecretKeyEntry.class.getSimpleName());
                    } else if (keyStore.entryInstanceOf(alias, TrustedCertificateEntry.class)) {
                        result.set(TrustedCertificateEntry.class.getSimpleName());
                    } else if (keyStore.entryInstanceOf(alias, PasswordEntry.class)) {
                        result.set(PasswordEntry.class.getSimpleName());
                    } else {
                        result.set("Other");
                    }
                } catch (KeyStoreException | RuntimeException e) {
                    ROOT_LOGGER.tracef(e, "Unable to populate %s", ENTRY_TYPE);
                    return;
                }

            }
        });

        resourceRegistration.registerReadOnlyAttribute(CERTIFICATE, new LdapKeyStoreRuntimeOnlyHandler(false) {

            @Override
            protected void performRuntime(ModelNode result, ModelNode operation, LdapKeyStoreService keyStoreService) throws OperationFailedException {
                String alias = alias(operation);

                KeyStore keyStore = keyStoreService.getValue();
                // If we have a certificate chain don't waste time reporting what would just be the first cert in the chain.
                try {
                    if (keyStore.getCertificateChain(alias) == null) {
                        Certificate cert = keyStore.getCertificate(alias);
                        if (cert != null) {
                            writeCertificate(result, cert);
                        }
                    }
                } catch (KeyStoreException | NoSuchAlgorithmException| RuntimeException | CertificateEncodingException e) {
                    ROOT_LOGGER.tracef(e, "Unable to populate %s", CERTIFICATE);
                    return;
                }
            }
        });

        resourceRegistration.registerReadOnlyAttribute(getNamedCertificateList(ElytronDescriptionConstants.CERTIFICATE_CHAIN), new LdapKeyStoreRuntimeOnlyHandler(false) {

            @Override
            protected void performRuntime(ModelNode result, ModelNode operation, LdapKeyStoreService keyStoreService) throws OperationFailedException {
                String alias = alias(operation);

                KeyStore keyStore = keyStoreService.getValue();
                try {
                    Certificate[] chain = keyStore.getCertificateChain(alias);
                    if (chain != null) {
                        writeCertificates(result, chain);
                    }

                } catch (KeyStoreException | CertificateEncodingException | NoSuchAlgorithmException | RuntimeException e) {
                    ROOT_LOGGER.tracef(e, "Unable to populate %s", ElytronDescriptionConstants.CERTIFICATE_CHAIN);
                    return;
                }
            }
        });
    }


    static String alias(ModelNode operation) {
        String aliasName = null;
        PathAddress pa = PathAddress.pathAddress(operation.require(OP_ADDR));
        for (int i = pa.size() - 1; i > 0; i--) {
            PathElement pe = pa.getElement(i);
            if (ElytronDescriptionConstants.ALIAS.equals(pe.getKey())) {
                aliasName = pe.getValue();
                break;
            }
        }

        if (aliasName == null) {
            throw ROOT_LOGGER.operationAddressMissingKey(ElytronDescriptionConstants.ALIAS);
        }

        return aliasName;
    }

    private static class RemoveHandler extends LdapKeyStoreRuntimeOnlyHandler {

        RemoveHandler() {
            super(true, true);
        }

        @Override
        protected void performRuntime(ModelNode result, ModelNode operation, LdapKeyStoreService keyStoreService) throws OperationFailedException {
            String alias = alias(operation);

            KeyStore keyStore = keyStoreService.getModifiableValue();

            try {
                keyStore.deleteEntry(alias);
            } catch (KeyStoreException e) {
                throw new OperationFailedException(e);
            }
        }

    }

}
