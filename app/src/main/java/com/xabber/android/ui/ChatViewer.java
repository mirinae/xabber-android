/**
 * Copyright (c) 2013, Redsolution LTD. All rights reserved.
 *
 * This file is part of Xabber project; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License, Version 3.
 *
 * Xabber is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License,
 * along with this program. If not, see http://www.gnu.org/licenses/.
 */
package com.xabber.android.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.view.ViewPager;
import android.support.v7.app.ActionBar;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;

import com.xabber.android.data.ActivityManager;
import com.xabber.android.data.Application;
import com.xabber.android.data.SettingsManager;
import com.xabber.android.data.account.AccountManager;
import com.xabber.android.data.account.OnAccountChangedListener;
import com.xabber.android.data.entity.BaseEntity;
import com.xabber.android.data.extension.archive.MessageArchiveManager;
import com.xabber.android.data.extension.attention.AttentionManager;
import com.xabber.android.data.extension.otr.OTRManager;
import com.xabber.android.data.extension.otr.SecurityLevel;
import com.xabber.android.data.intent.EntityIntentBuilder;
import com.xabber.android.data.message.AbstractChat;
import com.xabber.android.data.message.MessageManager;
import com.xabber.android.data.message.OnChatChangedListener;
import com.xabber.android.data.notification.NotificationManager;
import com.xabber.android.data.roster.AbstractContact;
import com.xabber.android.data.roster.OnContactChangedListener;
import com.xabber.android.data.roster.RosterManager;
import com.xabber.android.ui.adapter.ChatViewerAdapter;
import com.xabber.android.ui.helper.ContactTitleInflater;
import com.xabber.android.ui.helper.ManagedActivity;
import com.xabber.androiddev.R;

import java.util.Collection;
import java.util.HashSet;

/**
 * Chat activity.
 * <p/>
 *
 * @author alexander.ivanov
 */
public class ChatViewer extends ManagedActivity implements OnChatChangedListener,
        OnContactChangedListener, OnAccountChangedListener, ViewPager.OnPageChangeListener,
        ChatViewerAdapter.FinishUpdateListener, RecentChatFragment.RecentChatFragmentInteractionListener,
        ChatViewerFragment.ChatViewerFragmentListener {

    /**
     * Attention request.
     */
    private static final String ACTION_ATTENTION = "com.xabber.android.data.ATTENTION";

    private static final String SAVED_ACCOUNT = "com.xabber.android.ui.ChatViewer.SAVED_ACCOUNT";
    private static final String SAVED_USER = "com.xabber.android.ui.ChatViewer.SAVED_USER";
    private static final String SAVED_EXIT_ON_SEND = "com.xabber.android.ui.ChatViewer.EXIT_ON_SEND";

    private boolean exitOnSend;

    private String extraText = null;

    ChatViewerAdapter chatViewerAdapter;

    ViewPager viewPager;
    private View actionBarView;

    Collection<ChatViewerFragment> registeredChats = new HashSet<>();
    Collection<RecentChatFragment> recentChatFragments = new HashSet<>();

    private String actionWithAccount = null;
    private String actionWithUser = null;
    private int[] accountActionBarColors;
    private int[] accountStatusBarColors;
    private Window window;
    private int defaultStatusBarColor;

    private Animation shakeAnimation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (isFinishing()) {
            return;
        }

        Intent intent = getIntent();
        String account = getAccount(intent);
        String user = getUser(intent);

        if (hasAttention(intent)) {
            AttentionManager.getInstance().removeAccountNotifications(account, user);
        }

        if (savedInstanceState != null) {
            if (account == null || user == null) {
                account = savedInstanceState.getString(SAVED_ACCOUNT);
                user = savedInstanceState.getString(SAVED_USER);
            }
            exitOnSend = savedInstanceState.getBoolean(SAVED_EXIT_ON_SEND);
        }

        shakeAnimation = AnimationUtils.loadAnimation(this, R.anim.shake);

        window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            defaultStatusBarColor = window.getStatusBarColor();
        }

        accountActionBarColors = getResources().getIntArray(R.array.account_action_bar);
        accountStatusBarColors = getResources().getIntArray(R.array.account_status_bar);


        ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayShowTitleEnabled(false);
        actionBar.setDisplayShowHomeEnabled(false);
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setDisplayShowCustomEnabled(true);

        actionBarView = LayoutInflater.from(this).inflate(R.layout.chat_viewer_action_bar, null);

        actionBar.setCustomView(actionBarView, new ActionBar.LayoutParams(
                ActionBar.LayoutParams.MATCH_PARENT, ActionBar.LayoutParams.MATCH_PARENT));




        setContentView(R.layout.activity_chat_viewer);

        if (account != null && user != null) {
            chatViewerAdapter = new ChatViewerAdapter(getFragmentManager(),
                    account, user, this);
        } else {
            chatViewerAdapter = new ChatViewerAdapter(getFragmentManager(), this);
        }

        viewPager = (ViewPager) findViewById(R.id.pager);
        viewPager.setAdapter(chatViewerAdapter);
        viewPager.setOnPageChangeListener(this);

        selectPage(account, user, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Application.getInstance().addUIListener(OnChatChangedListener.class, this);
        Application.getInstance().addUIListener(OnContactChangedListener.class, this);
        Application.getInstance().addUIListener(OnAccountChangedListener.class, this);

        Intent intent = getIntent();
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            extraText = intent.getStringExtra(Intent.EXTRA_TEXT);

            if (extraText != null) {
                intent.removeExtra(Intent.EXTRA_TEXT);
                exitOnSend = true;
            }
        }

        if (actionWithAccount != null && actionWithUser != null) {
            updateActionBar(actionWithAccount, actionWithUser);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(SAVED_ACCOUNT, actionWithAccount);
        outState.putString(SAVED_USER, actionWithUser);
        outState.putBoolean(SAVED_EXIT_ON_SEND, exitOnSend);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Application.getInstance().removeUIListener(OnChatChangedListener.class, this);
        Application.getInstance().removeUIListener(OnContactChangedListener.class, this);
        Application.getInstance().removeUIListener(OnAccountChangedListener.class, this);
        MessageManager.getInstance().removeVisibleChat();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (isFinishing())
            return;

        chatViewerAdapter.updateChats();

        String account = getAccount(intent);
        String user = getUser(intent);
        if (account == null || user == null) {
            return;
        }

        selectPage(account, user, false);
    }

    private void selectPage(String account, String user, boolean smoothScroll) {
        int position = chatViewerAdapter.getPageNumber(account, user);
        selectPage(position, smoothScroll);
    }

    private void selectPage(int position, boolean smoothScroll) {
        viewPager.setCurrentItem(position, smoothScroll);
        onPageSelected(position);
    }

    private static String getAccount(Intent intent) {
        String value = EntityIntentBuilder.getAccount(intent);
        if (value != null)
            return value;
        // Backward compatibility.
        return intent.getStringExtra("com.xabber.android.data.account");
    }

    private static String getUser(Intent intent) {
        String value = EntityIntentBuilder.getUser(intent);
        if (value != null)
            return value;
        // Backward compatibility.
        return intent.getStringExtra("com.xabber.android.data.user");
    }

    private static boolean hasAttention(Intent intent) {
        return ACTION_ATTENTION.equals(intent.getAction());
    }

    public static Intent createIntent(Context context, String account,
                                      String user) {
        return new EntityIntentBuilder(context, ChatViewer.class).setAccount(account).setUser(user).build();
    }

    public static Intent createIntent(Context context) {
        return new EntityIntentBuilder(context, ChatViewer.class).build();
    }

    public static Intent createClearTopIntent(Context context, String account,
                                              String user) {
        Intent intent = createIntent(context, account, user);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return intent;
    }

    /**
     * Create intent to send message.
     * <p/>
     * Contact list will not be shown on when chat will be closed.
     *
     * @param context
     * @param account
     * @param user
     * @param text    if <code>null</code> then user will be able to send a number
     *                of messages. Else only one message can be send.
     * @return
     */
    public static Intent createSendIntent(Context context, String account,
                                          String user, String text) {
        Intent intent = ChatViewer.createIntent(context, account, user);
        intent.setAction(Intent.ACTION_SEND);
        intent.putExtra(Intent.EXTRA_TEXT, text);
        return intent;
    }

    public static Intent createAttentionRequestIntent(Context context,
                                                      String account, String user) {
        Intent intent = ChatViewer.createClearTopIntent(context, account, user);
        intent.setAction(ACTION_ATTENTION);
        return intent;
    }

    @Override
    public void onChatChanged(final String account, final String user,
                              final boolean incoming) {

        String currentAccount = null;
        String currentUser = null;
        AbstractChat chatByPageNumber = chatViewerAdapter.getChatByPageNumber(viewPager.getCurrentItem());

        if (chatByPageNumber != null) {
            currentAccount = chatByPageNumber.getAccount();
            currentUser = chatByPageNumber.getUser();
        }

        if (chatViewerAdapter.updateChats()) {
            selectPage(currentAccount, currentUser, false);
        } else {
            updateRegisteredChats();
            updateRegisteredRecentChatsFragments();

            for (ChatViewerFragment chat : registeredChats) {
                if (chat.isEqual(account, user)) {
                    chat.updateChat();
                }
            }
        }

        if (actionWithAccount != null && actionWithAccount.equals(account)
                && actionWithUser != null && actionWithUser.equals(user)) {
            updateActionBar(account, user);
            if (incoming) {
                actionBarView.findViewById(R.id.name_holder).startAnimation(shakeAnimation);
            }
        }


    }

    @Override
    public void onContactsChanged(Collection<BaseEntity> entities) {
        updateRegisteredChats();
        updateRegisteredRecentChatsFragments();

        if (actionWithAccount != null && actionWithUser != null) {
            updateActionBar(actionWithAccount, actionWithUser);
        }
    }

    @Override
    public void onAccountsChanged(Collection<String> accounts) {
        updateRegisteredChats();
        updateRegisteredRecentChatsFragments();

        if (actionWithAccount != null && actionWithUser != null) {
            updateActionBar(actionWithAccount, actionWithUser);
        }
    }

    void onSent() {
        if (exitOnSend) {
            close();
        }
    }

    void close() {
        finish();
        if (!Intent.ACTION_SEND.equals(getIntent().getAction())) {
            ActivityManager.getInstance().clearStack(false);
            if (!ActivityManager.getInstance().hasContactList(this)) {
                startActivity(ContactList.createIntent(this));
            }
        }
    }

    @Override
    public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {

    }

    @Override
    public void onPageSelected(int position) {
        AbstractChat selectedChat = chatViewerAdapter.getChatByPageNumber(position);

        if (selectedChat == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                window.setStatusBarColor(defaultStatusBarColor);
            }
            getSupportActionBar().setBackgroundDrawable(null);
            getSupportActionBar().setDisplayShowTitleEnabled(true);
            actionBarView.setVisibility(View.GONE);
            setTitle(getString(R.string.chat_list));
            MessageManager.getInstance().removeVisibleChat();
            return;
        }

        String account = selectedChat.getAccount();
        String user = selectedChat.getUser();

        updateActionBar(account, user);

        MessageManager.getInstance().setVisibleChat(account, user);

        MessageArchiveManager.getInstance().requestHistory(
                account, user, 0,
                MessageManager.getInstance().getChat(account, user).getRequiredMessageCount());

        NotificationManager.getInstance().removeMessageNotification(account, user);

        actionWithAccount = account;
        actionWithUser = user;
    }

    private void updateActionBar(String account, String user) {
        actionBarView.setVisibility(View.VISIBLE);
        getSupportActionBar().setDisplayShowTitleEnabled(false);
        final AbstractContact abstractContact = RosterManager.getInstance().getBestContact(account, user);
        ContactTitleInflater.updateTitle(actionBarView, this, abstractContact);

        int colorLevel = AccountManager.getInstance().getColorLevel(abstractContact.getAccount());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.setStatusBarColor(accountStatusBarColors[colorLevel]);
        }
        getSupportActionBar().setBackgroundDrawable(new ColorDrawable(accountActionBarColors[colorLevel]));

        SecurityLevel securityLevel = OTRManager.getInstance().getSecurityLevel(account, user);
        SettingsManager.SecurityOtrMode securityOtrMode = SettingsManager.securityOtrMode();
        ImageView securityView = (ImageView) actionBarView.findViewById(R.id.security);

        if (securityLevel == SecurityLevel.plain
                && (securityOtrMode == SettingsManager.SecurityOtrMode.disabled
                || securityOtrMode == SettingsManager.SecurityOtrMode.manual)) {
            securityView.setVisibility(View.GONE);
        } else {
            securityView.setVisibility(View.VISIBLE);
            securityView.setImageLevel(securityLevel.getImageLevel());
        }

    }

    private void updateRegisteredChats() {
        for (ChatViewerFragment chat : registeredChats) {
            chat.updateChat();
        }
    }

    private void updateRegisteredRecentChatsFragments() {
        for (RecentChatFragment recentChatFragment : recentChatFragments) {
            recentChatFragment.updateChats(chatViewerAdapter.getActiveChats());
        }
    }

    @Override
    public void onPageScrollStateChanged(int state) {
    }


    public void registerChat(ChatViewerFragment chat) {
        registeredChats.add(chat);
    }

    public void unregisterChat(ChatViewerFragment chat) {
        registeredChats.remove(chat);
    }

    public void registerRecentChatsList(RecentChatFragment recentChatFragment) {
        recentChatFragments.add(recentChatFragment);
    }

    public void unregisterRecentChatsList(RecentChatFragment recentChatFragment) {
        recentChatFragments.remove(recentChatFragment);
    }


    @Override
    public void onChatViewAdapterFinishUpdate() {
        insertExtraText();

        updateRegisteredChats();
    }

    private void insertExtraText() {

        if (extraText == null) {
            return;
        }

        boolean isExtraTextInserted = false;

        for (ChatViewerFragment chat : registeredChats) {
            if (chat.isEqual(actionWithAccount, actionWithUser)) {
                chat.setInputText(extraText);
                isExtraTextInserted = true;
            }
        }

        if (isExtraTextInserted) {
            extraText = null;
        }
    }

    @Override
    public void onRecentChatSelected(AbstractChat chat) {
        selectPage(chat.getAccount(), chat.getUser(), true);
    }

    @Override
    public void onRecentChatsCalled() {
        viewPager.setCurrentItem(chatViewerAdapter.getRecentChatsPosition(), true);
    }

    public ChatViewerAdapter getChatViewerAdapter() {
        return chatViewerAdapter;
    }
}
