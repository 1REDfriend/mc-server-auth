package dev.tongt.mcserverauth.flow;

import dev.tongt.mcserverauth.config.Messages;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Builds every dialog the plugin shows. Button actions are the constants below.
 *
 * <p>Each method returns a template: a function from a per-display nonce to the dialog. The nonce is put into
 * every button's click id as {@code <action>.<nonce>}, so {@link DialogWaiter} can ignore a click on a dialog that
 * is no longer the one being awaited.
 */
public final class DialogFactory {
    public static final String SUBMIT = "submit";
    public static final String CANCEL = "cancel";
    public static final String FORGOT = "forgot";
    public static final String SEND_CODE = "send_code";
    public static final String HAVE_CODE = "have_code";
    public static final String RESEND = "resend";
    public static final String BACK = "back";

    private static final int INPUT_WIDTH = 250;
    private static final int BUTTON_WIDTH = 120;
    private static final int EMAIL_MAX_LENGTH = 254;
    private static final int CODE_LENGTH = 6;

    private final Messages messages;
    private final int passwordMaxLength;

    public DialogFactory(Messages messages, int passwordMaxLength) {
        this.messages = messages;
        this.passwordMaxLength = passwordMaxLength;
    }

    public Function<String, Dialog> serverPassword(@Nullable Component error) {
        return build("dialog.server-password.title", body("dialog.server-password.body", error),
                List.of(password("password", "input.password")),
                List.of(button("button.submit", SUBMIT), button("button.cancel", CANCEL)), false);
    }

    /** @param withEmail show the optional email field; false when the server cannot send email */
    public Function<String, Dialog> register(@Nullable Component error, boolean withEmail) {
        List<DialogInput> inputs = new ArrayList<>(
                List.of(password("password", "input.password"), password("confirm", "input.confirm")));
        if (withEmail) {
            inputs.add(text("email", "input.email-optional", EMAIL_MAX_LENGTH));
        }
        return build("dialog.register.title",
                body(withEmail ? "dialog.register.body" : "dialog.register.body-no-email", error),
                inputs, List.of(button("button.submit", SUBMIT), button("button.cancel", CANCEL)), false);
    }

    public Function<String, Dialog> login(@Nullable Component error) {
        return build("dialog.login.title", body("dialog.login.body", error),
                List.of(password("password", "input.password")),
                List.of(button("button.login", SUBMIT), button("button.forgot", FORGOT), button("button.cancel", CANCEL)),
                false);
    }

    public Function<String, Dialog> forgotMenu(String maskedEmail, @Nullable Component error) {
        return build("dialog.forgot.title", body("dialog.forgot.body", error, email(maskedEmail)),
                List.of(),
                List.of(button("button.send-code", SEND_CODE), button("button.have-code", HAVE_CODE),
                        button("button.back", BACK)),
                false);
    }

    /** Code entry for email verification (reset = false) or password reset (reset = true). */
    public Function<String, Dialog> code(boolean reset, String maskedEmail, @Nullable Component error) {
        String title = reset ? "dialog.reset-code.title" : "dialog.verify-email.title";
        String bodyKey = reset ? "dialog.reset-code.body" : "dialog.verify-email.body";
        return build(title, body(bodyKey, error, email(maskedEmail)),
                List.of(text("code", "input.code", CODE_LENGTH)),
                List.of(button("button.confirm", SUBMIT), button("button.resend", RESEND), button("button.back", BACK),
                        button("button.cancel", CANCEL)),
                false);
    }

    /** New password + confirm. Used for reset, and by admins to set the server password. */
    public Function<String, Dialog> newPassword(String titleKey, @Nullable Component error, boolean closable) {
        return build(titleKey, error == null ? List.of() : List.of(DialogBody.plainMessage(error)),
                List.of(password("password", "input.new-password"), password("confirm", "input.confirm")),
                List.of(button("button.submit", SUBMIT), button("button.cancel", CANCEL)), closable);
    }

    public Function<String, Dialog> changePassword(@Nullable Component error) {
        return build("dialog.change-password.title", error == null ? List.of() : List.of(DialogBody.plainMessage(error)),
                List.of(password("current", "input.current-password"), password("password", "input.new-password"),
                        password("confirm", "input.confirm")),
                List.of(button("button.submit", SUBMIT), button("button.cancel", CANCEL)), true);
    }

    /** /email: current password plus the address to add. @param currentMasked null when no email is set yet */
    public Function<String, Dialog> setEmail(@Nullable String currentMasked, @Nullable Component error) {
        List<DialogBody> body = currentMasked == null
                ? body("dialog.set-email.body-none", error)
                : body("dialog.set-email.body", error, email(currentMasked));
        return build("dialog.set-email.title", body,
                List.of(password("current", "input.current-password"), text("email", "input.new-email", EMAIL_MAX_LENGTH)),
                List.of(button("button.submit", SUBMIT), button("button.cancel", CANCEL)), true);
    }

    /** A button before its click id is known. */
    private record Button(Component label, String action) {}

    /** Click id value for one action on one displayed dialog. */
    static String clickId(String action, String nonce) {
        return action + "." + nonce;
    }

    private Function<String, Dialog> build(String titleKey, List<DialogBody> body, List<DialogInput> inputs,
                                           List<Button> buttons, boolean closable) {
        DialogBase base = DialogBase.builder(messages.get(titleKey))
                .canCloseWithEscape(closable)
                .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
                .body(body)
                .inputs(inputs)
                .build();
        // Four buttons read better as a 2 x 2 grid than as a row of three plus one.
        int columns = buttons.size() == 4 ? 2 : Math.min(buttons.size(), 3);
        return nonce -> {
            List<ActionButton> actions = buttons.stream()
                    .map(b -> ActionButton.builder(b.label())
                            .width(BUTTON_WIDTH)
                            .action(DialogAction.customClick(
                                    Key.key(DialogWaiter.NAMESPACE, clickId(b.action(), nonce)), null))
                            .build())
                    .toList();
            DialogType type = DialogType.multiAction(actions).columns(columns).build();
            return Dialog.create(factory -> factory.empty().base(base).type(type));
        };
    }

    private List<DialogBody> body(String key, @Nullable Component error, TagResolver... resolvers) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(messages.get(key, resolvers)));
        if (error != null) {
            body.add(DialogBody.plainMessage(error));
        }
        return body;
    }

    private static TagResolver email(String maskedEmail) {
        return Placeholder.unparsed("email", maskedEmail);
    }

    private DialogInput password(String id, String labelKey) {
        return text(id, labelKey, passwordMaxLength);
    }

    private DialogInput text(String id, String labelKey, int maxLength) {
        return DialogInput.text(id, messages.get(labelKey)).width(INPUT_WIDTH).maxLength(maxLength).build();
    }

    private Button button(String labelKey, String action) {
        return new Button(messages.get(labelKey), action);
    }
}
