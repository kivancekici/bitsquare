/*
 * This file is part of Bitsquare.
 *
 * Bitsquare is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bitsquare is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bitsquare. If not, see <http://www.gnu.org/licenses/>.
 */

package io.bitsquare.p2p;


import io.bitsquare.p2p.listener.SendMessageListener;

import java.security.PublicKey;

public interface MessageService extends P2PService {

    void sendMessage(Peer peer, Message message, SendMessageListener listener);

    void sendMessage(Peer peer, Message message, PublicKey p2pSigPubKey, PublicKey p2pEncryptPubKey, SendMessageListener listener);

    void addMessageHandler(MessageHandler listener);

    void removeMessageHandler(MessageHandler listener);
}
