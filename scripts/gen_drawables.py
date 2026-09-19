#!/usr/bin/env python3
"""Generates the icon + background drawables for the Mercan Android app (run from res/)."""
import os

files = {}

def vec(path, tint='@color/ink'):
    return (f'<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" '
            f'android:viewportWidth="24" android:viewportHeight="24" android:tint="{tint}">\n'
            f'    <path android:fillColor="#FFFFFF" android:pathData="{path}"/>\n</vector>\n')

icons = {
 'ic_menu': "M3,18h18v-2H3v2zM3,13h18v-2H3v2zM3,6v2h18V6H3z",
 'ic_new': "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z",
 'ic_send': "M2.01,21L23,12 2.01,3 2,10l15,2 -15,2z",
 'ic_stop': "M6,6h12v12H6z",
 'ic_bulb': "M9,21c0,0.55 0.45,1 1,1h4c0.55,0 1,-0.45 1,-1v-1H9v1zM12,2C8.14,2 5,5.14 5,9c0,2.38 1.19,4.47 3,5.74V17c0,0.55 0.45,1 1,1h6c0.55,0 1,-0.45 1,-1v-2.26c1.81,-1.27 3,-3.36 3,-5.74 0,-3.86 -3.14,-7 -7,-7zM14,13.7V16h-4v-2.3C8.48,12.63 7,11.53 7,9c0,-2.76 2.24,-5 5,-5s5,2.24 5,5c0,2.49 -1.51,3.65 -3,4.7z",
 'ic_bulb_on': "M9,21c0,0.55 0.45,1 1,1h4c0.55,0 1,-0.45 1,-1v-1H9v1zM12,2C8.14,2 5,5.14 5,9c0,2.38 1.19,4.47 3,5.74V17c0,0.55 0.45,1 1,1h6c0.55,0 1,-0.45 1,-1v-2.26c1.81,-1.27 3,-3.36 3,-5.74 0,-3.86 -3.14,-7 -7,-7z",
 'ic_chevron': "M7.41,8.59L12,13.17l4.59,-4.58L18,10l-6,6 -6,-6 1.41,-1.41z",
 'ic_more': "M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z",
 'ic_arrow': "M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z",
 'ic_search': "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zm-6,0C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z",
 'ic_settings': "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94 0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6 3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z",
 'ic_info': "M11,7h2v2h-2zM11,11h2v6h-2zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8z",
 'ic_delete': "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM8,9h8v10H8V9zM15.5,4l-1,-1h-5l-1,1H5v2h14V4z",
 'ic_edit': "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z",
 'ic_copy': "M16,1H4c-1.1,0 -2,0.9 -2,2v14h2V3h12V1zM19,5H8c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2zM19,21H8V7h11v14z",
 'ic_refresh': "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z",
 'ic_back': "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z",
 'ic_check': "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z",
 'ic_chat': "M20,2H4c-1.1,0 -2,0.9 -2,2v18l4,-4h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM20,16H5.17L4,17.17V4h16v12z",
 'ic_shield': "M12,1L3,5v6c0,5.55 3.84,10.74 9,12 5.16,-1.26 9,-6.45 9,-12V5l-9,-4zM12,11.99h7c-0.53,4.12 -3.28,7.79 -7,8.94V12H5V6.3l7,-3.11v8.8z",
 'ic_history': "M13,3c-4.97,0 -9,4.03 -9,9H1l3.89,3.89 0.07,0.14L9,12H6c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08V8H12z",
}
for k, v in icons.items():
    files[f'drawable/{k}.xml'] = vec(v)

XML = '<?xml version="1.0" encoding="utf-8"?>\n'
NS = 'xmlns:android="http://schemas.android.com/apk/res/android"'

def shape(body, kind='rectangle'):
    return f'{XML}<shape {NS} android:shape="{kind}">\n{body}</shape>\n'

def ripple(color, inner):
    return f'{XML}<ripple {NS} android:color="{color}">\n    <item>\n{inner}    </item>\n</ripple>\n'

def inner_shape(body, kind='rectangle'):
    return f'        <shape android:shape="{kind}">\n{body}        </shape>\n'

files['drawable/bubble_user.xml'] = shape(
    '    <solid android:color="@color/user_bubble" />\n'
    '    <corners android:topLeftRadius="18dp" android:topRightRadius="18dp" android:bottomLeftRadius="18dp" android:bottomRightRadius="5dp" />\n')

files['drawable/bg_thinking.xml'] = f'''{XML}<layer-list {NS}>
    <item>
        <shape android:shape="rectangle">
            <solid android:color="@color/surface_alt" />
            <stroke android:width="1dp" android:color="@color/line_soft" />
            <corners android:radius="12dp" />
        </shape>
    </item>
    <item android:top="8dp" android:bottom="8dp" android:left="0dp" android:right="0dp" android:gravity="left">
        <shape android:shape="rectangle">
            <solid android:color="@color/brand" />
            <corners android:radius="3dp" />
            <size android:width="3dp" />
        </shape>
    </item>
</layer-list>
'''

files['drawable/bg_input_pill.xml'] = shape(
    '    <solid android:color="@color/surface" />\n    <stroke android:width="1dp" android:color="@color/line" />\n    <corners android:radius="26dp" />\n')
files['drawable/bg_send.xml'] = ripple('#33FFFFFF', inner_shape('            <solid android:color="@color/brand" />\n', 'oval'))
files['drawable/bg_stop.xml'] = ripple('#33FFFFFF', inner_shape('            <solid android:color="@color/ink" />\n', 'oval'))
files['drawable/bg_icon_button.xml'] = (f'{XML}<ripple {NS} android:color="@color/ripple">\n    <item android:id="@android:id/mask">\n'
    '        <shape android:shape="oval">\n            <solid android:color="#000000" />\n        </shape>\n    </item>\n</ripple>\n')
card_body = ('            <solid android:color="@color/surface" />\n            <stroke android:width="1dp" android:color="@color/line" />\n'
             '            <corners android:radius="14dp" />\n')
files['drawable/bg_card.xml'] = ripple('@color/ripple', inner_shape(card_body))
files['drawable/bg_card_static.xml'] = shape(
    '    <solid android:color="@color/surface" />\n    <stroke android:width="1dp" android:color="@color/line" />\n    <corners android:radius="14dp" />\n')
files['drawable/bg_field.xml'] = f'''{XML}<selector {NS}>
    <item android:state_focused="true">
        <shape android:shape="rectangle">
            <solid android:color="@color/surface" />
            <stroke android:width="1.5dp" android:color="@color/brand" />
            <corners android:radius="10dp" />
        </shape>
    </item>
    <item>
        <shape android:shape="rectangle">
            <solid android:color="@color/surface" />
            <stroke android:width="1dp" android:color="@color/line" />
            <corners android:radius="10dp" />
        </shape>
    </item>
</selector>
'''
files['drawable/bg_search.xml'] = shape('    <solid android:color="@color/paper" />\n    <corners android:radius="12dp" />\n')
files['drawable/bg_conversation_item.xml'] = f'''{XML}<ripple {NS} android:color="@color/ripple">
    <item>
        <selector>
            <item android:state_selected="true">
                <shape android:shape="rectangle">
                    <solid android:color="@color/brand_soft" />
                    <corners android:radius="10dp" />
                </shape>
            </item>
            <item>
                <shape android:shape="rectangle">
                    <solid android:color="@android:color/transparent" />
                    <corners android:radius="10dp" />
                </shape>
            </item>
        </selector>
    </item>
</ripple>
'''
files['drawable/bg_button_primary.xml'] = ripple('#33FFFFFF', inner_shape('            <solid android:color="@color/brand" />\n            <corners android:radius="14dp" />\n'))
files['drawable/bg_button_secondary.xml'] = ripple('@color/ripple', inner_shape(card_body))
files['drawable/bg_badge.xml'] = shape('    <solid android:color="@color/brand_soft" />\n    <corners android:radius="8dp" />\n')
files['drawable/bg_avatar.xml'] = shape('    <solid android:color="@color/brand_soft" />\n', 'oval')
files['drawable/bg_popup.xml'] = shape('    <solid android:color="@color/surface" />\n    <stroke android:width="1dp" android:color="@color/line" />\n    <corners android:radius="12dp" />\n')
files['drawable/dot_active.xml'] = shape('    <solid android:color="@color/brand" />\n    <corners android:radius="4dp" />\n    <size android:width="22dp" android:height="8dp" />\n')
files['drawable/dot_inactive.xml'] = shape('    <solid android:color="@color/line" />\n    <size android:width="8dp" android:height="8dp" />\n', 'oval')
files['drawable/divider_h.xml'] = shape('    <solid android:color="@color/line_soft" />\n    <size android:height="1dp" />\n')

for k, v in files.items():
    os.makedirs(os.path.dirname(k), exist_ok=True)
    with open(k, 'w', encoding='utf-8', newline='\n') as f:
        f.write(v)
print(len(files), 'drawables written')
